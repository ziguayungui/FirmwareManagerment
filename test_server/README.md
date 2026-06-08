# 固件升级测试服务器
# 启动命令: python -m http.server 8080 --directory test_server

## 使用说明

1. 将固件ZIP文件放入此目录，命名为 firmware_v2.0.1.zip
2. 修改 update_info.txt 中的版本号、MD5和文件名
3. 启动服务器后，APP配置地址为: http://<本机IP>:8080

## 文件结构
- update_info.txt   # 更新信息文件
- firmware_v2.0.1.zip  # 固件文件（需要自行放入）

## 服务器启动
python -m http.server 8080 --directory test_server

## 测试步骤
1. 启动服务器
2. 在APP中配置服务器地址为 http://<IP>:8080
3. 点击检查更新，应能检测到新版本