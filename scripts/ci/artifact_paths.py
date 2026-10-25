"""Write/remove receipts through anchored directory handles without following links."""
from contextlib import contextmanager
import os
from pathlib import Path
import secrets
import stat


@contextmanager
def parent_handle(root, target, create):
    root = Path(root).resolve()
    target = Path(target)
    if not target.is_absolute():
        target = root / target
    try:
        parts = target.relative_to(root).parts
    except ValueError:
        raise ValueError('Artifact path escapes repository') from None
    if not parts or '..' in parts:
        raise ValueError('Invalid artifact path')
    descriptor = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in parts[:-1]:
            if create:
                try:
                    os.mkdir(part, mode=0o700, dir_fd=descriptor)
                except FileExistsError:
                    pass
            next_descriptor = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                                      dir_fd=descriptor)
            os.close(descriptor)
            descriptor = next_descriptor
        yield descriptor, parts[-1]
    finally:
        os.close(descriptor)


def regular_target(descriptor, name):
    try:
        information = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
    except FileNotFoundError:
        return
    if not stat.S_ISREG(information.st_mode):
        raise ValueError('Artifact target must be a regular file without symlinks')


def remove_artifact(root, target):
    try:
        with parent_handle(root, target, create=False) as (descriptor, name):
            regular_target(descriptor, name)
            try:
                os.unlink(name, dir_fd=descriptor)
            except FileNotFoundError:
                pass
    except FileNotFoundError:
        pass


def write_artifact(root, target, text):
    with parent_handle(root, target, create=True) as (descriptor, name):
        regular_target(descriptor, name)
        temporary = '.receipt-' + secrets.token_hex(12)
        handle = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                         0o600, dir_fd=descriptor)
        try:
            with os.fdopen(handle, 'w', encoding='utf-8') as stream:
                stream.write(text)
            regular_target(descriptor, name)
            os.replace(temporary, name, src_dir_fd=descriptor, dst_dir_fd=descriptor)
        finally:
            try:
                os.unlink(temporary, dir_fd=descriptor)
            except FileNotFoundError:
                pass
