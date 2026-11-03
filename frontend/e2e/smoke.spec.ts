import { expect, test } from '@playwright/test';

/**
 * 买家端冒烟用例。
 *
 * <p><b>只测不需要后端也能断言的页面</b>：登录页、协议页、路由守卫。
 * 首页、宠物列表、订单页都依赖 `/api/v1/**` 返回真实数据，纳入 e2e 就必须先起
 * MySQL + Redis + 三个 Java 服务，CI 里跑不动，本地也容易因为环境问题误报。
 * 这些页面留到"有全栈环境"的场景再补，而不是用 mock 假装通过。
 *
 * <p>这里没有拦截 API：登录页本身不请求业务数据，请求失败只会让页面出现错误提示，
 * 不影响表单与控制台的断言。
 */
test.describe('买家端冒烟', () => {
  test('登录页渲染手机号与验证码表单', async ({ page }) => {
    await page.goto('/login');
    await expect(page.getByRole('heading', { name: '登录茸茸星球' })).toBeVisible();
    await expect(page.getByLabel('手机号')).toBeVisible();
    await expect(page.getByLabel('短信验证码')).toBeVisible();
  });

  test('未登录访问需要登录的页面会跳转到登录', async ({ page }) => {
    await page.goto('/orders');
    // 守卫是 meta.auth + 未登录 → /login?redirect=...
    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByRole('heading', { name: '登录茸茸星球' })).toBeVisible();
  });

  test('用户协议无需登录即可阅读', async ({ page }) => {
    await page.goto('/terms');
    await expect(page.getByRole('heading', { name: '用户服务协议', level: 1 })).toBeVisible();
    // 断言正文确实渲染，而不是只有一个标题骨架。
    await expect(page.getByRole('heading', { name: /适用范围与服务内容/ })).toBeVisible();
  });

  test('协议与隐私政策可互相跳转', async ({ page }) => {
    await page.goto('/terms');
    await page.getByRole('link', { name: '隐私政策' }).click();
    await expect(page).toHaveURL(/\/privacy/);
    await expect(page.getByRole('heading', { name: '隐私政策', level: 1 })).toBeVisible();
  });
});
