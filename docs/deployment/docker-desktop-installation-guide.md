# Docker Desktop Windows 安装指南

## 📥 方法 1：使用 winget 自动安装（推荐）

打开 **PowerShell（管理员权限）**，执行：

```powershell
# 使用 winget 安装 Docker Desktop
winget install Docker.DockerDesktop

# 安装完成后需要重启计算机
```

---

## 📥 方法 2：手动下载安装

### 步骤 1：下载安装包

访问官网下载页面：
```
https://www.docker.com/products/docker-desktop/
```

或直接下载链接：
```
https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe
```

### 步骤 2：运行安装程序

1. 双击下载的 `Docker Desktop Installer.exe`
2. 在安装界面勾选：
   - ✅ Use WSL 2 instead of Hyper-V（推荐）
   - ✅ Add shortcut to desktop
3. 点击 "OK" 开始安装
4. 等待安装完成（约5-10分钟）

### 步骤 3：重启计算机

安装完成后必须重启计算机才能使用Docker。

---

## ⚙️ 首次启动配置

### 1. 启动 Docker Desktop

重启后，从开始菜单打开 **Docker Desktop**

### 2. 接受服务条款

首次启动会要求接受 Docker Subscription Service Agreement

### 3. 选择配置（推荐设置）

- **Use WSL 2**: 推荐启用（性能更好）
- **Start Docker Desktop when you sign in**: 推荐启用（开机自动启动）
- **Send usage statistics**: 可选

### 4. 等待Docker引擎启动

系统托盘会出现Docker鲸鱼图标，等待图标停止转动（约1-2分钟）

---

## ✅ 验证安装

打开 **PowerShell**，执行：

```powershell
# 检查Docker版本
docker --version

# 应该输出类似：
# Docker version 24.0.x, build xxxxx

# 检查Docker Compose版本
docker-compose --version

# 应该输出类似：
# Docker Compose version v2.x.x
```

---

## 🔧 故障排查

### 问题 1：安装失败 - WSL 2 未启用

**错误信息**：WSL 2 installation is incomplete

**解决方法**：

```powershell
# 以管理员身份运行PowerShell
# 启用 WSL 功能
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart

# 启用虚拟机平台
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart

# 重启计算机
Restart-Computer

# 重启后，安装WSL 2 Linux内核更新包
# 下载地址：https://aka.ms/wsl2kernel
# 或使用winget安装：
wsl --install
```

### 问题 2：Docker Desktop 无法启动

**解决方法**：
1. 检查Hyper-V是否启用（设置 → 应用 → 可选功能 → Hyper-V）
2. 检查BIOS中是否启用虚拟化（Intel VT-x 或 AMD-V）
3. 以管理员身份运行Docker Desktop

### 问题 3：权限问题

**错误信息**：You are not allowed to use Docker

**解决方法**：
将当前用户添加到 docker-users 组：
```powershell
# 以管理员身份运行
net localgroup docker-users "Administrator" /add
```

---

## 📝 安装完成后的下一步

安装并验证Docker成功后，回到梧曜星枢项目：

```powershell
# 1. 进入项目目录
cd C:\Users\Administrator\梧曜AI\infra\compose

# 2. 启动基础设施
docker-compose up -d

# 3. 验证服务
docker-compose ps

# 4. 运行验证脚本
cd C:\Users\Administrator\梧曜AI
node infra/scripts/verify-infrastructure.js
```

---

## 🎯 快速安装命令（管理员PowerShell）

```powershell
# 一键安装Docker Desktop
winget install Docker.DockerDesktop

# 如果winget不可用，启用WSL2
wsl --install

# 重启计算机
Restart-Computer
```

安装完成后，请通知我，我们继续进行基础设施验证。

---

**预计安装时间**: 10-20分钟（包括下载和重启）  
**所需磁盘空间**: 约3GB  
**系统要求**: Windows 10/11 Pro/Enterprise/Education（Home版本需要先升级到Pro或使用WSL2）
