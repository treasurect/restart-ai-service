
#!/bin/bash

set -Eeuo pipefail

# ==============================
# 1. 基础配置
# ==============================

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ENV_FILE="/Users/treasure/Project/config/restart-server.env"

SERVER_USER="treasure"
SERVER_HOST="124.222.46.122"
REMOTE="${SERVER_USER}@${SERVER_HOST}"

REMOTE_DIR="/opt/restart-ai"
REMOTE_JAR="${REMOTE_DIR}/restart-ai-service.jar"

SERVICE_NAME="restart-ai"
SERVICE_FILE="/etc/systemd/system/restart-ai.service"

SERVER_ENV_FILE="/etc/restart-server/restart-server.env"

cd "$PROJECT_DIR"

echo "================================"
echo "开始发布 restart-ai"
echo "================================"

# ==============================
# 2. 加载本地环境变量
# ==============================

if [[ ! -f "$ENV_FILE" ]]; then
    echo "错误：本地环境变量文件不存在：$ENV_FILE"
    exit 1
fi

set -a
source "$ENV_FILE"
set +a

echo "本地环境变量加载成功"

# ==============================
# 3. Maven 打包
# ==============================

echo "开始 Maven 打包..."

if [[ -x "$PROJECT_DIR/mvnw" ]]; then
    "$PROJECT_DIR/mvnw" clean package -DskipTests
else
    mvn clean package -DskipTests
fi

# 定位唯一的可执行 JAR
shopt -s nullglob
JARS=()

for file in "$PROJECT_DIR"/target/restart-ai-service*.jar; do
    [[ -f "$file" ]] || continue

    case "$file" in
        *-sources.jar|-javadoc.jar|-plain.jar|*.original)
            continue
            ;;
    esac

    JARS+=("$file")
done

shopt -u nullglob

if [[ ${#JARS[@]} -ne 1 ]]; then
    echo "错误：没有找到唯一的 restart-ai-service JAR"
    printf '%s\n' "${JARS[@]}"
    exit 1
fi

JAR_FILE="${JARS[0]}"

echo "打包成功：$JAR_FILE"

# ==============================
# 4. 创建服务器部署目录
# ==============================

echo "准备服务器部署目录..."

ssh "$REMOTE" "
    sudo mkdir -p '$REMOTE_DIR' &&
    sudo chown -R '$SERVER_USER:$SERVER_USER' '$REMOTE_DIR'
"

# ==============================
# 5. 检查服务器环境变量文件
# ==============================

echo "检查服务器环境变量文件..."

ssh "$REMOTE" "
    test -f '$SERVER_ENV_FILE'
" || {
    echo "错误：服务器环境变量文件不存在：$SERVER_ENV_FILE"
    exit 1
}

# ==============================
# 6. 上传 JAR
# ==============================

echo "正在上传 JAR..."

scp "$JAR_FILE" "$REMOTE:$REMOTE_JAR"

echo "JAR 上传成功"

# ==============================
# 7. 创建 systemd 服务
# ==============================

echo "配置 systemd 服务..."

ssh "$REMOTE" "sudo tee '$SERVICE_FILE' > /dev/null << 'EOF'
[Unit]
Description=Restart AI Service
After=network.target mysql.service

[Service]
Type=simple
User=treasure
WorkingDirectory=/opt/restart-ai
EnvironmentFile=/etc/restart-server/restart-server.env
ExecStart=/usr/bin/java -jar /opt/restart-ai/restart-ai-service.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable '$SERVICE_NAME'
"

# ==============================
# 8. 重启服务
# ==============================

echo "正在重启 restart-ai..."

if ! ssh "$REMOTE" "sudo systemctl restart '$SERVICE_NAME'"; then
    echo "服务重启命令执行失败"
    ssh "$REMOTE" "sudo journalctl -u '$SERVICE_NAME' -n 80 --no-pager"
    exit 1
fi

# ==============================
# 9. 检查服务状态
# ==============================

echo "检查服务状态..."

sleep 3

if ! ssh "$REMOTE" "sudo systemctl is-active --quiet '$SERVICE_NAME'"; then
    echo "================================"
    echo "restart-ai 启动失败"
    echo "================================"

    ssh "$REMOTE" "
        sudo systemctl status '$SERVICE_NAME' --no-pager || true
        sudo journalctl -u '$SERVICE_NAME' -n 80 --no-pager
    "

    exit 1
fi

# ==============================
# 10. 发布成功
# ==============================

echo "================================"
echo "restart-ai 发布成功"
echo "================================"

ssh "$REMOTE" "
    sudo systemctl status '$SERVICE_NAME' --no-pager

    echo '========== 最近启动日志 =========='

    sudo journalctl -u '$SERVICE_NAME' -n 30 --no-pager
"
