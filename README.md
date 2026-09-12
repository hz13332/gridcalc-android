# 网格交易收益计算器 - 安卓版

用 Kivy 写的原生安卓程序,计算逻辑和 Windows 版完全一致。

## 拿 APK(不用自己装环境)

1. 把本文件夹所有文件传到一个新的 GitHub 仓库。
2. 打开仓库的 Actions 页面,等 Build APK 跑完(约 15-25 分钟)。
3. 在 Artifacts 里下载 GridCalc-apk,解压得到 apk。
4. 传到手机上点安装即可,安装时允许“未知来源应用”。

## 自己在 Linux 上构建

```bash
pip install buildozer cython==0.29.36
buildozer android debug
# 生成的包在 bin/ 目录
```

## 说明

- 首次点计算默认参数就是 469U、150x、55000~150000、触发 77000、11 格、每格 0.001。
- 程序离线运行,不联网。
- 中文显示调用安卓系统自带中文字体,无需额外字库。
