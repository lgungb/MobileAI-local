---
name: calculate-hash
description: 计算给定文本的哈希值。
---

# 计算哈希

本技能计算给定文本的哈希值。

## 示例

* "计算...的哈希"
* "...的哈希是什么"

## 使用说明

调用 `run_js` 工具，传入以下精确参数：

- 脚本名: `index.html`
- data: JSON 字符串，包含以下字段
  - text: 要计算哈希的文本
