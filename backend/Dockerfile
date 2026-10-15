# Eden AI 代理后端 —— 云托管镜像
# 基础镜像：官方 Python 3.12 精简版，体积小、启动快
FROM python:3.12-slim

# 不写 .pyc、日志实时输出（不带缓冲，便于云托管控制台查看日志）
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PORT=80

# 工作目录：容器内的 /app。代码包直接解压到这里，
# 因此最终结构为 /app/app/main.py，ASGI 路径即 app.main:app。
WORKDIR /app

# 先装依赖再拷代码，最大化利用镜像层缓存（改代码不用重装依赖）
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 拷贝后端全部代码（.dockerignore 已排除 venv / __pycache__ / 日志 / 密钥等）
COPY . .

# 云托管容器内部端口固定 80
EXPOSE 80

# 启动命令：监听 0.0.0.0:80，绝不加 --reload（线上开 reload 会导致反复重启）
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "80"]
