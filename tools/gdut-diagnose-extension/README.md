# 广工课表诊断扩展

给 LumaSchedule 排查「广东工业大学教务导入」用的本地 Chrome 扩展。  
**不会自动扫接口**：只有你点击扩展图标时，才会在**当前已登录页面**里发约 4 次请求，并下载一份 JSON。

## 安装

1. Chrome 打开 `chrome://extensions`
2. 右上角打开「开发者模式」
3. 「加载已解压的扩展程序」→ 选择本目录  
   `tools/gdut-diagnose-extension`

## 使用

1. 在 Chrome 里正常登录广工教务 `https://jxfw.gdut.edu.cn`
2. 停在教务首页或「课表查询」页
3. 点工具栏上的「广工课表诊断」图标
4. 选择保存位置，得到 `gdut-diagnose-*.json`
5. 把这个 JSON 发给开发者

## 请求上限

| # | 请求 |
|---|------|
| 1 | GET `xsgrkbcx!getXsgrbkList.action` |
| 2 | GET `xsgrkbcx!getXsgrbkList.action?xnxqdm=当前学期` |
| 3 | GET `xsgrkbcx!xsAllKbList.action?xnxqdm=当前学期` |
| 4 | POST `xsgrkbcx!getDataList.action`（仅当前学期一次） |

只针对下拉框里当前选中的学期（一般是 `202601`），不会遍历其它学期。
