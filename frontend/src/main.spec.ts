import { ApplicationRef } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { vi } from 'vitest';

vi.mock('@angular/platform-browser', async (original) => ({
  ...await original<typeof import('@angular/platform-browser')>(), bootstrapApplication: vi.fn(),
}));

describe('Bootstrap', () => {
  beforeEach(() => { vi.resetModules(); document.body.innerHTML = '<app-root></app-root>'; });
  afterEach(() => { vi.clearAllMocks(); document.body.replaceChildren(); });

  it('boots the standalone shell with its application providers', async () => {
    vi.mocked(bootstrapApplication).mockResolvedValue({} as ApplicationRef);
    await import('./main');
    expect(bootstrapApplication).toHaveBeenCalledOnce();
    expect(vi.mocked(bootstrapApplication).mock.calls[0][1]?.providers.length).toBe(3);
    expect(document.querySelector('app-root')).not.toBeNull();
  });

  it('shows a safe reload message if startup fails', async () => {
    vi.mocked(bootstrapApplication).mockRejectedValue(new Error('Synthetic failure with private context'));
    await import('./main'); await Promise.resolve();
    expect(document.body.textContent).toBe('The application could not start. Please reload the page.');
    expect(document.body.textContent).not.toContain('private context');
  });
});
