<template>
 <section class="container page auth-layout" aria-label="暖爪账号登录">
  <RouterLink to="/" class="brand auth-brand">暖爪 <span>paw &amp; home</span></RouterLink>
  <div class="auth-columns"><div class="auth-form"><slot /></div><slot name="wechat" /></div>
 </section>
</template>
<style scoped>
/* 仅参考双栏排版；颜色、字体、输入框与按钮沿用网站现有设计变量。 */
.auth-layout{max-width:640px;min-height:calc(100dvh - 310px);padding-top:50px;padding-bottom:70px}
.auth-brand{display:flex;justify-content:center;align-items:baseline;gap:12px;margin-bottom:46px;font-size:38px}
.auth-brand span{font-size:24px}
.auth-columns{display:grid;grid-template-columns:minmax(0,1fr) 240px;gap:40px;align-items:start}
.auth-form{min-width:0}
.auth-form :deep(form){display:flex;flex-direction:column;gap:22px}
.auth-form :deep(p){margin:0}
.auth-form :deep(.auth-field){display:flex;flex-direction:row;align-items:center;gap:0;min-height:48px;border:1px solid var(--line);border-radius:12px;background:var(--surface);overflow:hidden}
.auth-form :deep(.auth-field:focus-within){border-color:var(--brand);outline:2px solid var(--peach);outline-offset:2px}
.auth-form :deep(.auth-field input){min-width:0;border:0;outline:none;background:transparent;border-radius:0;box-shadow:none;font-size:14px}
.auth-form :deep(.phone-prefix){padding-left:16px;font-size:14px;white-space:nowrap}
.auth-form :deep(.phone-prefix+input){padding-left:10px}
.auth-form :deep(.send-code){flex-shrink:0;background:none;color:var(--brand);border:0;border-left:1px solid var(--line);padding:0 16px;font-size:13px;line-height:20px}
.auth-form :deep(.login-consent){font-size:12px;gap:8px;line-height:1.8;color:var(--muted)}
.auth-form :deep(.login-consent input){min-height:0;width:15px;height:15px;flex-shrink:0;accent-color:var(--brand)}
.auth-form :deep(.auth-caption){font-size:12px;line-height:1.8;color:var(--muted);padding-left:23px}
.auth-form :deep(.auth-submit){margin-top:12px}
.auth-form :deep(.auth-links){display:flex;justify-content:center;gap:12px;flex-wrap:wrap;font-size:12px;color:var(--muted)}
.auth-form :deep(.auth-links a){text-decoration:underline;text-underline-offset:3px}
.auth-form :deep(.auth-links a:hover){color:var(--brand)}
.auth-form :deep(.notice),.auth-form :deep(.error-text){font-size:12px;line-height:1.8}
.auth-form :deep(.auth-title){position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap}
@media(max-width:640px){.auth-layout{max-width:380px;padding-top:30px;padding-bottom:40px}.auth-brand{font-size:32px;margin-bottom:32px}.auth-brand span{font-size:21px}.auth-columns{display:flex;flex-direction:column;gap:30px}.auth-form{width:100%}.auth-columns :deep(.wechat-panel){width:100%}}
</style>
