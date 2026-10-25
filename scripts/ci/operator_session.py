"""Drive an interactive terminal program through a pseudo-terminal with synthetic input only.

The transcript stays in memory and is never returned or written; callers receive only the text matched
by an explicit expectation, so password prompts (which are not echoed) cannot leak through it.
"""
import base64
import hashlib
import hmac
import os
import pty
import re
import select
import struct
import subprocess
import time


def totp(base32_key, moment=None, step=30):
    """RFC 6238 six-digit SHA-1 code for a base32 key without padding."""
    key = base64.b32decode(base32_key + '=' * (-len(base32_key) % 8))
    digest = hmac.new(key, struct.pack('>Q', int((time.time() if moment is None else moment) // step)), hashlib.sha1).digest()
    offset = digest[-1] & 15
    return '%06d' % ((struct.unpack('>I', digest[offset:offset + 4])[0] & 0x7fffffff) % 1_000_000)


class Session:
    def __init__(self, command, cwd, env, timeout=180):
        self.master, slave = pty.openpty()
        self.process = subprocess.Popen(command, cwd=cwd, env=env, stdin=slave, stdout=slave, stderr=slave, close_fds=True)
        os.close(slave)
        self.timeout, self.buffer = timeout, ''

    def expect(self, pattern):
        """Waits for pattern in unread output and returns its match; raises when the process ends or times out."""
        deadline = time.monotonic() + self.timeout
        compiled = re.compile(pattern)
        while True:
            found = compiled.search(self.buffer)
            if found:
                self.buffer = self.buffer[found.end():]
                return found
            if time.monotonic() > deadline:
                raise TimeoutError('OPERATOR_SESSION_TIMEOUT')
            if select.select([self.master], [], [], 1)[0]:
                try:
                    chunk = os.read(self.master, 4096)
                except OSError:
                    chunk = b''
                if not chunk:
                    raise EOFError('OPERATOR_SESSION_ENDED')
                self.buffer += chunk.decode('utf-8', 'replace')

    def send(self, text):
        os.write(self.master, (text + '\n').encode())

    def finish(self):
        """Returns the exit status and the not-yet-consumed output (used only for negative assertions)."""
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            if select.select([self.master], [], [], 0.2)[0]:
                try:
                    chunk = os.read(self.master, 4096)
                except OSError:
                    chunk = b''
                if chunk:
                    self.buffer += chunk.decode('utf-8', 'replace')
                    continue
            if self.process.poll() is not None:
                break
        status = self.process.wait(timeout=30)
        os.close(self.master)
        return status, self.buffer
