---
name: send-email
description: 发送电子邮件。
---

# 发送邮件

## 使用说明

调用 `run_intent` 工具，传入以下精确参数：

- intent: send_email
- parameters: JSON 字符串，包含以下字段：
  - extra_email: 收件人邮箱地址。字符串。
  - extra_subject: 邮件主题。字符串。
  - extra_text: 邮件正文。字符串。
