<template>
  <section class="container page auth-page" aria-label="茸茸星球账号登录">
    <!-- 左侧大照片与首页首屏同一句话；手机上隐藏，只留表单 -->
    <figure class="auth-photo" aria-hidden="true">
      <img src="/images/pets/golden-ball.jpg" alt="" />
      <figcaption>
        <strong>看中了，<br />先占个位。</strong>
        <span>线上不扣款，到店看过再付款。</span>
      </figcaption>
    </figure>
    <div class="auth-layout">
      <RouterLink to="/" class="brand auth-brand">茸茸星球</RouterLink>
      <div class="auth-columns">
        <div class="auth-form"><slot /></div>
        <slot name="wechat" />
      </div>
    </div>
  </section>
</template>
<style scoped>
/* 左图右表单；颜色、字体、输入框与按钮沿用网站现有设计变量。 */
.auth-page {
  display: grid;
  grid-template-columns: minmax(0, 0.85fr) minmax(0, 1fr);
  gap: clamp(32px, 5vw, 72px);
  align-items: center;
  padding-top: 40px;
  padding-bottom: 72px;
}
.auth-photo {
  position: relative;
  margin: 0;
  height: min(640px, calc(100dvh - 180px));
  min-height: 460px;
  border-radius: var(--radius);
  overflow: hidden;
}
.auth-photo img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 65% 50%;
}
.auth-photo figcaption {
  position: absolute;
  inset: auto 0 0;
  padding: 120px 32px 32px;
  background: linear-gradient(to top, #3d2f22d9, #3d2f2200);
  color: #fff;
}
.auth-photo strong {
  display: block;
  font-family: var(--display);
  font-weight: 900;
  font-size: clamp(36px, 3.6vw, 52px);
  line-height: 1.08;
  color: var(--tag);
}
.auth-photo span {
  display: block;
  margin-top: 12px;
  font-size: 14px;
  color: #ffffffd9;
}
.auth-layout {
  max-width: 640px;
  width: 100%;
}
.auth-brand {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 40px;
  font-size: clamp(40px, 4vw, 56px);
}
.auth-brand span {
  font-size: 24px;
}
.auth-columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 240px;
  gap: 40px;
  align-items: start;
}
.auth-form {
  min-width: 0;
}
.auth-form :deep(form) {
  display: flex;
  flex-direction: column;
  gap: 22px;
}
.auth-form :deep(p) {
  margin: 0;
}
.auth-form :deep(.auth-field) {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 0;
  min-height: 48px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-sm);
  background: var(--surface);
  overflow: hidden;
}
.auth-form :deep(.auth-field:focus-within) {
  border-color: var(--brand);
  outline: 2px solid var(--sage);
  outline-offset: 2px;
}
.auth-form :deep(.auth-field input) {
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  border-radius: 0;
  box-shadow: none;
  font-size: 14px;
}
.auth-form :deep(.phone-prefix) {
  padding-left: 16px;
  font-size: 14px;
  white-space: nowrap;
}
.auth-form :deep(.phone-prefix + input) {
  padding-left: 10px;
}
.auth-form :deep(.send-code) {
  flex-shrink: 0;
  background: none;
  color: var(--brand);
  border: 0;
  border-left: 1px solid var(--line);
  padding: 0 16px;
  font-size: 13px;
  line-height: 20px;
}
.auth-form :deep(.login-consent) {
  font-size: 12px;
  gap: 8px;
  line-height: 1.8;
  color: var(--muted);
}
.auth-form :deep(.login-consent input) {
  min-height: 0;
  width: 15px;
  height: 15px;
  flex-shrink: 0;
  accent-color: var(--brand);
}
.auth-form :deep(.auth-caption) {
  font-size: 12px;
  line-height: 1.8;
  color: var(--muted);
  padding-left: 23px;
}
.auth-form :deep(.auth-submit) {
  margin-top: 12px;
}
.auth-form :deep(.auth-links) {
  display: flex;
  justify-content: center;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--muted);
}
.auth-form :deep(.auth-links a) {
  text-decoration: underline;
  text-underline-offset: 3px;
}
.auth-form :deep(.auth-links a:hover) {
  color: var(--brand);
}
.auth-form :deep(.notice),
.auth-form :deep(.error-text) {
  font-size: 12px;
  line-height: 1.8;
}
.auth-form :deep(.auth-title) {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
.auth-columns :deep(.wechat-panel) {
  border-radius: var(--radius);
}
@media (max-width: 1100px) {
  .auth-page {
    grid-template-columns: 1fr;
  }
  .auth-photo {
    display: none;
  }
  .auth-layout {
    margin-inline: auto;
  }
  .auth-brand {
    justify-content: center;
  }
}
@media (max-width: 640px) {
  .auth-page {
    padding-top: 30px;
    padding-bottom: 40px;
  }
  .auth-layout {
    max-width: 380px;
  }
  .auth-brand {
    font-size: 32px;
    margin-bottom: 32px;
  }
  .auth-brand span {
    font-size: 21px;
  }
  .auth-columns {
    display: flex;
    flex-direction: column;
    gap: 30px;
  }
  .auth-form {
    width: 100%;
  }
  .auth-columns :deep(.wechat-panel) {
    width: 100%;
  }
}
</style>
