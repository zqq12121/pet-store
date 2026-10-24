import { expect, test } from '@playwright/test';

/**
 * 手机视口用例（iPhone 13，390×844）。
 *
 * <p>买家端大量页面是移动优先布局，桌面浏览器看不出"横向溢出"这类问题——
 * 而它恰好是最常见的移动端缺陷：某个长单词、固定宽度的图片、没加
 * {@code box-sizing} 的输入框都能把整页撑宽，用户于是能左右拖动。
 *
 * <p>这里只查登录页：它包含输入框、按钮、第三方登录入口，是布局最容易出问题的一屏，
 * 且不依赖后端数据。
 */
test('登录页在手机视口下没有横向溢出', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: '登录暖爪' })).toBeVisible();

  const overflow = await page.evaluate(() => {
    const doc = document.documentElement;
    // 留 1px 容差：部分设备像素比下 scrollWidth 会比 innerWidth 大 1。
    return doc.scrollWidth > window.innerWidth + 1;
  });
  expect(overflow, '页面出现横向滚动，说明有元素宽度超出视口').toBe(false);
});
