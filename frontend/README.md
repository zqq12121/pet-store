# 暖爪 · 宠物门店前端

Vue 3 + TypeScript + Vite 买家网站（手机 / 电脑）与 Element Plus 店主管理后台。沿用已确认的 Figma 奶油白、暖橙和宠物照片设计。

## 1. 启动演示

需要 Node.js 22.12+（开发验证使用 24.15）。在当前目录执行：

```bash
npm ci
npm run dev
```

- 买家端：http://127.0.0.1:5173
- 店主后台：http://127.0.0.1:5174
- 本地演示 API：127.0.0.1:8787，仅监听本机。
- 停止：终端按 Ctrl+C。重启会重置订单、上传文件、账户和知识条目。

演示买家：任意格式正确的手机号，例如 `13800000000`；图片码 `DEMO`，短信码 `123456`。
演示店主：`admin / WarmPaw2026`；图片码 `DEMO`。
这些仅是本机测试值，不会发送真实短信，也不会扣款。

若 npm 提示原有缓存权限错误，可使用独立缓存运行 `npm ci --cache /private/tmp/warmpaw-npm-cache`，无需修改全局缓存权限。

## 2. 可操作流程

1. 首页 / 分类筛选 → 宠物档案 → 立即购买。
2. 手机号登录 → 填写同一手机号与联系人 → 阅读演示协议 → 提交。
3. 点击“模拟支付成功（无扣款）” → 查看自提码。
4. 买家完成四项健康勾选、协议勾选和本人演示短信确认。
5. 店主进入订单管理 → 办理到店核销 → 输入自提码 → 核对检疫信息 → 确认交付。
6. 买家订单详情可申请售后；健康申诉需上传测试材料。店主审核、记录退回后可查看模拟退款结果。退回个体保持下架。

后台还可管理宠物档案、照片 / 视频、检疫图片、上下架、知识文章 / 问答对、导入任务、索引任务、门店及轮播。AI 页面支持游客提问、流式展示、来源、反馈及登录后的本人订单卡片。

## 3. 切换 Spring Boot 后端

复制 `.env.example` 为 `.env.local`，按实际端口修改：

```dotenv
VITE_API_TARGET=http://127.0.0.1:8080
VITE_DEMO_MODE=false
VITE_ADMIN_BASE=/
```

分别启动两个前端，无需演示服务：

```bash
npm run dev:buyer
npm run dev:admin
```

重启 Vite 后生效。请求前缀统一为 `/api/v1`，以项目附带接口文档为对接契约。`shared/api.ts` 处理独立买家 / 管理员会话、响应包装、错误和幂等键；`shared/types.ts` / `adminTypes.ts` 定义页面需要的数据。

真实模式隐藏演示登录提示和模拟支付按钮。生产构建默认真实模式；如需构建演示包，必须显式设置 `VITE_DEMO_MODE=true`。不得把演示包当作正式商城发布。

微信 H5 / JSAPI、OAuth 绑定、真实短信、真实退款、OSS 上传、DeepSeek / RAG 及数据库事务需要后端实现并联调。电脑付款显示站内续付二维码和链接，手机需登录同一账户；正式域名需能由手机访问，本机 127.0.0.1 链接不能跨设备使用。

## 4. 目录与检查

```text
buyer-web/        买家 Vue 应用，页面按路由懒加载
admin-web/        店主 Vue 应用
shared/           类型、请求、加载状态、SSE 解析和测试
public/images/    原设计采用的宠物照片
server/demo.mjs   开发专用内存演示 API，非生产后端
```

```bash
npm test          # 流式 UTF-8 / CRLF 分块及错误处理
npm run typecheck # Vue + TypeScript
npm run build     # dist/buyer 与 dist/admin
```

## 5. 部署示例

推荐买家与后台分别使用独立域名，均以 `/` 为基础路径。分别将 `dist/buyer`、`dist/admin` 放入两个 Nginx 站点根目录，API 转发到同一个 Spring Boot 服务：

```nginx
location / {
    try_files $uri $uri/ /index.html;
}
location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;
    proxy_read_timeout 120s;
}
```

示例需放进实际 HTTPS 站点配置。不要部署 `server/demo.mjs` 或 `node_modules` 为静态资源。若后台与买家同域，用 `/admin/`，构建前设置 `VITE_ADMIN_BASE=/admin/`，后台产物放在站点 `admin` 子目录，并为 `/admin/` 配置 `try_files $uri $uri/ /admin/index.html`。

## 6. 当前边界

- 这是可运行前端及开发演示适配器，尚无真实后端联调和支付商户验证。
- 演示 AI 是固定示例内容；索引重建只模拟任务结果；Excel/CSV 导入提交链路已接，演示 API 会明确返回“不执行文件导入”，不会伪报解析成功。
- 换宠规则尚未确定，按接口文档默认关闭；当前交付覆盖全额退款、退回与治疗费分担，不含换宠交付操作。
- 商品照片和演示资料不代表真实品种、健康或销售证明。上线须替换门店信息、照片、凭证及交易协议。
- 当前原生输入自提码支持后台核销；未接入摄像头扫码。手机买家有响应式布局，后台主要针对电脑。
- 生产构建通过；Element Plus 整体引入仍有包体积提示，未进行生产网络性能或真实 iOS / 微信环境验收。
- 收藏、购物车、评价、多商家、支付宝等仍遵循需求文档的一期裁剪范围。
