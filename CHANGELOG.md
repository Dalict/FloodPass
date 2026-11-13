# Changelog / 更新日志

## 1.0.1
- Added config self-healing and migration mechanism for config.yml / 新增 config.yml 自愈与迁移机制
  - Checks the layout against the bundled defaults on startup and reload / 启动与重载时对照内置默认结构校验
  - Migrates previous valid values into the new layout automatically / 自动将旧配置中的有效数值迁移进新结构
  - Backs up the original before rewriting / 重写前自动备份原文件
  - Corrupt files are safely reset to defaults / 配置损坏时安全回退默认值
- Bundled 15 additional built-in languages / 内置新增 15 种语言
  (ru, es, pt_br, de, fr, zh_tw, ja, ko, tr, pl, it, nl, vi, th, id)
- Version bump to 1.0.1 / 版本号升级至 1.0.1

## 1.0.0
- First version release / 首个版本发布
