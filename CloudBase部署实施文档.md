# Eden 后端 CloudBase 云托管部署实施文档

> 目标：把 `c:\Users\suwei\Desktop\eden\backend` 的 FastAPI 项目，**不上 git**，用「本地打包 zip → 控制台直接上传代码包」的方式部署到腾讯云 CloudBase 云托管（CloudBase Run），最终拿到平台自带测试域名，手机 4G 可直接访问 `/docs`，电脑关机后端持续运行。

---

## 一、交付文件清单（根目录 = `backend/`）

| 文件 | 作用 |
|---|---|
| `app/main.py` | FastAPI 主程序，实例名 `app`，含全部接口 |
| `app/__init__.py` | 包标识，使 `app.main:app` 可被 import |
| `Dockerfile` | 镜像构建，容器端口固定 80 |
| `requirements.txt` | 全部依赖 |
| `.dockerignore` | 构建时排除 venv/缓存/日志/密钥 |
| `.env.example` | 环境变量模板（本地用，线上在控制台填） |

---

## 二、硬性约束落实情况

| # | 约束 | 落实 |
|---|---|---|
| 1 | 不用 git，本地 zip 上传 | 代码包上传方式，见第四节 |
| 2 | 容器内部端口固定 80 | `EXPOSE 80` + `--port 80`，`DEFAULT_PORT=80` |
| 3 | 移除 `--reload` | `main.py` 与 Dockerfile `CMD` 均已去掉 |
| 4 | `backend/app/main.py`、实例名 `app` | 已就位 |
| 5 | 自动 CORS | `CORSMiddleware`，`allow_origins=["*"]` |
| 6 | 环境变量配置，不硬编码 | Key/代理/DB/SMTP/JWT 全部 `os.getenv` |
| 7 | `GET /health` 返回 `{"status":"ok"}` | 已实现，实测 200 |
| 8 | Dockerfile + 补全依赖 + 控制台参数清单 | 见第三、五节 |
| 9 | zip 根目录为 backend 内容，排除 venv/缓存/日志 | 见第四节 |
| 10 | 自带测试域名、手机 4G 可访问 | 见第六节 |

### 额外的稳定性增强（已写入 main.py）
- 上游超时默认 **60s**（`REQUEST_TIMEOUT`）。
- **指数退避重试**：超时 / 5xx 自动重试，最多 3 次，间隔 1s / 2s / 4s（`UPSTREAM_MAX_RETRIES`、`UPSTREAM_RETRY_BASE_DELAY`）。
- 完整打印请求体、上游状态码与耗时，便于区分「网络超时」还是「模型推理超时」。

---

## 三、Dockerfile 与启动命令

```dockerfile
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PORT=80
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
EXPOSE 80
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "80"]
```

- **控制台启动命令**：`uvicorn app.main:app --host 0.0.0.0 --port 80`
- 本地开发：`uvicorn app.main:app --host 0.0.0.0 --port 80`

---

## 四、打包上传步骤（PowerShell）

```powershell
cd c:\Users\suwei\Desktop\eden\backend
Compress-Archive -Path .\app, .\Dockerfile, .\requirements.txt `
  -DestinationPath ..\eden-backend.zip -Force
```

产物：`c:\Users\suwei\Desktop\eden\eden-backend.zip`

```
eden-backend.zip
├── app/main.py
├── app/__init__.py
├── Dockerfile
└── requirements.txt
```

> 只打包必需项，天然排除 venv / `__pycache__` / `*.log` / `eden.db*` / `.env`，包体最小。

---

## 五、控制台部署参数清单

| 配置项 | 填写值 |
|---|---|
| 服务名称 | `eden-backend` |
| 部署方式 | **本地代码包上传**（不是 git） |
| 上传文件 | `eden-backend.zip` |
| Dockerfile 路径 | 默认 `/Dockerfile` |
| 容器端口 | `80` |
| 启动命令 | `uvicorn app.main:app --host 0.0.0.0 --port 80` |
| 健康检查路径 | `/health`，期望 200，返回 `{"status":"ok"}` |
| 规格 | 1 实例，0.5 核 / 1G（按需调整） |

### 环境变量

| 变量 | 必填 | 值示例 |
|---|---|---|
| `ARK_API_KEY` | 是 | 火山方舟 API Key |
| `ARK_MODEL_NAME` | 是 | `doubao-pro-32k` 或 `ep-2024xxxx` |
| `ARK_BASE_URL` | 否 | `https://ark.cn-beijing.volces.com/api/v3/` |
| `REQUEST_TIMEOUT` | 否 | `60` |
| `UPSTREAM_MAX_RETRIES` | 否 | `3` |
| `SMTP_HOST` | 是 | `smtp.qq.com` |
| `SMTP_PORT` | 是 | `465` |
| `SMTP_USER` | 是 | `xxx@qq.com` |
| `SMTP_AUTH_CODE` | 是 | QQ 邮箱授权码 |
| `JWT_SECRET` | 建议 | 长随机串（多实例必须一致） |
| `PORT` | 否 | `80` |

---

## 六、Step-by-step 控制台操作清单

1. **登录**：浏览器打开腾讯云 CloudBase 控制台 → 登录 → 选择/新建环境。
2. **进入云托管**：左侧「云托管 CloudBase Run」→「服务管理」→「新建服务」。
3. **建服务**：服务名 `eden-backend`，创建方式选「从本地代码包上传」。
4. **上传**：上传 `eden-backend.zip`，平台自动解压识别根目录 Dockerfile。
5. **填参数**：容器端口 `80`；启动命令 `uvicorn app.main:app --host 0.0.0.0 --port 80`。
6. **健康检查**：路径 `/health`，初始延迟 5s、间隔 10s、超时 3s、失败阈值 3。
7. **环境变量**：按第五节表格逐条添加。
8. **规格并部署**：1 实例 / 0.5 核 / 1G → 部署 → 等状态「正常/运行中」。
9. **拿测试域名**：服务详情页「访问配置」中的默认测试域名（形如 `https://xxxx.ap-shanghai.app.tcloudbase.com`）。
10. **验证**：
    - `https://<测试域名>/health` → `{"status":"ok"}`
    - `https://<测试域名>/docs` → Swagger 文档
    - `POST https://<测试域名>/api/chat` → 正常返回
11. **长期运行**：平台托管容器，本地电脑可完全关机。

---

## 七、注意事项

- 云托管容器为**无状态**：默认 SQLite（`app/eden.db`）在容器重建后会丢失。若账号/同步数据需长期保存，请在控制台挂载持久化存储或改用云数据库。
- 手机 4G 可访问后，把测试域名填到 Android 端 `ApiConfig` / `BuildConfig` 的 Base URL。
- 若上游大模型反复超时，优先调大 `REQUEST_TIMEOUT` 并确认 `ARK_BASE_URL` 可达。
