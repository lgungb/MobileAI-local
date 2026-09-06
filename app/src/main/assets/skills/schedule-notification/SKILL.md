---
name: schedule-notification
description: 安排特定日期或每日重复的通知。
---

# 安排通知

## 使用说明

要安排通知，必须遵循以下精确步骤：
1. 首先，如果通知不需要每日重复，调用 `run_intent` 工具，`intent` 设为 `get_current_date_and_time`，`parameters` 设为 `{}`，获取用户的本地日期和时间。然后在回复中明确计算安排的日期和时间。写出：
- 今天的确切日期。
- 用户请求的目标日期或相对时间（例如"明天"、"本周五"）。
- 需要在今天日期上加上的确切天数。
- 最终计算的日期，确保如果加上的天数超过当前月的天数，正确滚动到下一个月或年。
2. 调用 `run_intent` 工具，传入以下精确参数：
- intent: schedule_notification
- parameters: JSON 字符串，包含以下字段：
   - title: 通知标题。字符串。
   - message: 通知消息内容。字符串。
   - hour: 通知的小时（0-23）。整数。
   - minute: 通知的分钟（0-59）。整数。
   - task_id: （可选）目标页面的任务ID（例如"llm_agent_chat"）。字符串。
   - model_name: （可选）目标页面的模型名称（例如"Gemma-4-E4B-it"）。字符串。
   - deeplink: （可选）点击通知时打开的完整深链URI。字符串。
   - year: （可选）通知的年份。整数。
   - month: （可选）通知的月份（1-12）。整数。
   - day: （可选）通知的日期（1-31）。整数。
   - repeat_daily: （可选）如果通知应在此时间每日重复则为true。布尔值。
