# 🚀 Android Debug 自动部署规则

## 📌 工作目录
C:\Users\s30356\Desktop\trae\tv

---

## 🔁 每次执行流程（开发模式）

### 1️⃣ 构建 Debug 包

执行：
.\gradlew assembleDebug

如果失败：
- 停止流程
- 输出错误

---

### 2️⃣ APK 路径

app/build/outputs/apk/debug/app-debug.apk

---

### 3️⃣ 检查设备

adb devices

---

### 4️⃣ 安装 Debug APK

adb install -r app/build/outputs/apk/debug/app-debug.apk

---

## 🎯 触发词

以下全部视为“开发运行”：

- 运行
- run
- debug
- 安装
- 部署

---

## ⚠️ 强制规则

- 一律使用 debug 包
- 不允许使用 release，除非用户明确说明