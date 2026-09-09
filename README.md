# KaelorvynPlaytime

## 项目说明

- 名称：KaelorvynPlaytime
- 编号：0006
- 开始日期：2026-08-09
- 类型：Velocity 代理端游玩时长记录插件
- 主包：`com.kael.playtime`
- 状态：v1 已构建并部署到 `D:\MC\server\[25565] 代理端\plugins`

## 功能

- 玩家连入 Velocity 代理端时记录登录时间
- 玩家断开代理端时记录登出时间
- 自动累计总游玩时长
- 自动记录玩家在每个后端服务器的具体游玩时长
- 代理端全局统计：玩家跨大厅、生存、小游戏等子服时不会重复计算
- 每 60 秒自动持久化在线时长，异常崩溃最多丢失一个保存周期
- 首次启动会自动迁移旧 BloodPlaytime 的 `plugins/playtime` 数据
- 中文命令：`/playtime`、`/playtime <玩家>`、`/playtime log <玩家> [条数]`、
  `/playtime servers <玩家> [条数]`、`/playtime top [条数]`

## 目录

- `src/main/java`：插件源码
- `src/main/resources`：`velocity-plugin.json` 与默认配置
- `scripts/build.ps1`：编译、打包、部署
- `scripts/smoke-test.ps1`：纯逻辑自检
- `outputs`：最终 jar
- `logs`：构建与验证日志

## 数据文件

插件数据目录为代理端 `plugins/kaelorvynplaytime`：

- `config.properties`：保存间隔、时区配置
- `players/<uuid>.properties`：累计时长、当前会话、各服务器累计时长
- `sessions/<uuid>.tsv`：每次登录/登出历史

## 部署命令

```powershell
.\scripts\build.ps1
```

部署后需要重启 Velocity 代理端，插件才会加载。

## 权限

- 普通玩家：只能使用 `/playtime` 查询自己的游玩时长
- 查询其他玩家、登录日志、分服务器时长、排行榜：需要 `playtime.admin`（OP/管理员）
- 代理端控制台：始终可用
