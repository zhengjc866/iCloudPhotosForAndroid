# iCloud 照片接口概要

这份文档记录本项目用到的 iCloud Photos Web API 关键结论，方便理解代码为什么这么写。
内容是对 iCloud 网页版行为的观察总结，**不是** Apple 官方文档，Apple 随时可能调整，行为变化不另行通知。

> 这里刻意不包含任何账号相关数据（DSID、Cookie 值、签名 URL、分区主机名），也不提供可直接复用的抓包样本。

## 1. 认证

1. 用 WebView 打开 `https://www.icloud.com.cn/photos/`（中国区）或 `https://www.icloud.com/photos/`（全球区）完成 Apple ID 登录。
2. 登录成功后，从 `CookieManager` 读取 `www.icloud.com[.cn]` 域的 Cookie，关键的是：
   - `X-APPLE-WEBAUTH-USER`：账号身份，值里带 DSID；
   - `X-APPLE-WEBAUTH-TOKEN` / `X-APPLE-WEBAUTH-VALIDATE`：会话票据；
   - 各服务的 `X-APPLE-WEBAUTH-PCS-*`：分服务授权。
3. 中国区与全球区的 Cookie **域不同**（`.icloud.com.cn` vs `.icloud.com`），两套都要读并合并，否则会"网页已登录但 App 拿不到会话"。

## 2. 换取服务地址

```
POST https://setup.icloud.com[.cn]/setup/ws/1/validate
  ?clientBuildNumber=<build>&clientMasteringNumber=<mastering>&clientId=<uuid>
Body: null            ← JSON 字面量 null，认证完全依赖 Cookie
```

成功响应里取：

| 字段 | 用途 |
| --- | --- |
| `dsInfo.dsid` | 后续所有请求的 `dsid` 参数 |
| `webservices.ckdatabasews.url` | CloudKit 数据库地址，形如 `https://pXX-ckdatabasews.icloud.com:443` |
| `webservices.photos.url` | 照片服务地址（本项目未使用） |

错误码 `421` 通常表示 Cookie 与主机不匹配（用错了 `.com` / `.com.cn`），并非会话失效；本项目会依次尝试两个主机。

## 3. 查询照片

```
POST {ckdatabasews}/database/1/com.apple.photos.cloud/production/private/records/query
  ?clientBuildNumber=<build>&clientMasteringNumber=<mastering>&dsid=<dsid>&clientId=<uuid>
```

请求体关键字段：

| 字段 | 值 | 说明 |
| --- | --- | --- |
| `query.recordType` | `CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted` | 按拍摄日期排序的索引，已隐藏/已删除不在内 |
| `query.filterBy` | `[{"comparator":"EQUALS","fieldName":"direction","fieldValue":{"type":"STRING","value":"ASCENDING"}}]` | `direction` 是伪字段 |
| 同上 | `{"comparator":"EQUALS","fieldName":"startRank","fieldValue":{"type":"INT64","value":N}}` | 从第 N 条开始 |
| `zoneID` | `{"zoneName":"PrimarySync","zoneType":"REGULAR_CUSTOM_ZONE"}` | 固定 |
| `desiredKeys` | 字段白名单 | 减少响应体积 |
| `resultsLimit` | `200` | 一页返回"资产 + 主记录"对，约 100 张照片 |

### rank 语义（重要）

- `startRank = 0` 对应**最新**一张；rank 递增 = 更旧。
- 所以 `direction=ASCENDING` + 递增 `startRank` 才是"由近到远"；`DESCENDING` + 从末尾开始会拿到最旧的照片。
- 分页游标用"本页 master 记录数"累加，不能用"解析成功的照片数"（两者可能不等）。

### 时间过滤不可用

试图用 `filterBy` 加 `assetDate` 条件（`TIMESTAMP` 或 `INT64`）时，服务端**不报错但会静默忽略**，返回的仍是最新一页。
因此本项目用"rank 定位"实现按时间筛选：

1. 把目标时间段的结束时间映射成 rank：在 `[0, count)` 上并行采样若干 rank，读该 rank 页的 `assetDate`，二分收窄；
2. 得到一个**下界**（该 rank 的照片仍不早于目标），从它开始分页，按日期过滤，遇到早于窗口起点的照片即结束；
3. 需要精确张数时，在边界页内按日期逐条数出精确 rank（`countInRange`）。

### 智能相册不可用

所有 `CPLAssetAndMasterInSmartAlbumByAssetDate` + `smartAlbum` 组合都返回 `400 unknown smart album type`。
因此"照片 / 视频 / 收藏"分类在客户端按 `mediaType` / `favorite` 过滤。

## 4. 资源与下载

每个资产由 `CPLAsset`（元数据，含 `assetDate`、`favorite`、`isDeleted`、`masterRef`）和 `CPLMaster`（资源地址，`filenameEnc`、`itemType`、各 `res*Res`）配对组成。

| 资源字段 | 用途 |
| --- | --- |
| `resJPEGThumbRes` | 网格缩略图 |
| `resJPEGMedRes` | 查看器预览图 |
| `resJPEGFullRes` | 无 Med 时的兜底 |
| `resOriginalRes` | 原图 |
| `resVidMedRes` | 视频在线播放（中码流） |
| `resOriginalVidComplRes` | 视频原片 |

- `downloadURL` 是 CDN 的**短时签名地址**（约 1 小时），请求时要带会话 Cookie；过期后返回 403/410，可用 `/records/lookup` 按 master `recordName` 重新取地址。
- `duration` 单位是**毫秒**（不是秒）。

## 5. 分页陷阱

一页按 `resultsLimit` 返回的是"资产 + 主记录"混排，分页边界可能把 `CPLAsset` 和它的 `CPLMaster` 切到两页。若不处理，这一对被丢弃，表现为**照片数量偏少、每页少一张**。
本项目在解析到"有 asset 但缺 master"时，用 `/records/lookup` 补查缺失的 master。

## 6. 错误码速查

| 现象 | 常见原因 |
| --- | --- |
| `421` | Cookie 域与 setup 主机不匹配（中国区用 `setup.icloud.com` 等） |
| `401` / `success:false` | 会话过期，需要重新登录 |
| `403` / `410`（CDN） | 签名 URL 过期，重新 lookup |
| `400 unknown smart album type` | 不支持智能相册过滤 |
| `400 BAD_REQUEST` | 查询体字段/类型不对（例如把 `direction` 当普通字段） |

## 7. 相关实现

- `app/src/main/java/com/icloudalbum/app/data/IcloudApi.kt`：认证、查询、时间定位、下载
- `app/src/main/java/com/icloudalbum/app/data/SessionStore.kt`：会话持久化
- `docs/icloud-protocol.md`：本文（协议结论速查）
- `CHANGELOG.md`：逐版本变更记录
