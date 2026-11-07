import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { auth, clearAuth, headers, saveAuth } from './api';

function memoryStorage(): Storage {
  const data = new Map<string, string>();
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (key: string) => data.get(key) ?? null,
    key: (index: number) => [...data.keys()][index] ?? null,
    removeItem: (key: string) => void data.delete(key),
    setItem: (key: string, value: string) => void data.set(key, value),
  };
}

let local: Storage, session: Storage;
beforeEach(() => {
  local = memoryStorage();
  session = memoryStorage();
  vi.stubGlobal('localStorage', local);
  vi.stubGlobal('sessionStorage', session);
});
afterEach(() => vi.unstubAllGlobals());

it('买家登录态存 localStorage，不含 token，也不发 Authorization（靠 HttpOnly Cookie）', () => {
  saveAuth('buyer', { expiresIn: 86400, user: { nickname: '小明', phoneMasked: '138****0000' } });
  expect(local.getItem('warmpaw:buyer:auth')).not.toBeNull();
  expect(session.length).toBe(0);
  expect(auth('buyer')?.user?.nickname).toBe('小明');
  expect(headers('buyer')).toEqual({});
  clearAuth('buyer');
  expect(auth('buyer')).toBeNull();
});

it('管理端仍用 sessionStorage 里的 Bearer token', () => {
  saveAuth('admin', { accessToken: 'admin-token', expiresIn: 1800 });
  expect(local.length).toBe(0);
  expect(headers('admin')).toEqual({ Authorization: 'Bearer admin-token' });
});

it('过期的买家登录态视为未登录并被清理', () => {
  local.setItem('warmpaw:buyer:auth', JSON.stringify({ expiresAt: Date.now() - 1 }));
  expect(auth('buyer')).toBeNull();
  expect(local.getItem('warmpaw:buyer:auth')).toBeNull();
});
