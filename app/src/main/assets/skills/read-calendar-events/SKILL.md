---
name: read-calendar-events
description: 读取特定日期的系统日历事件。
---

# 读取日历事件

## 使用说明

要读取特定日期的日历事件，必须遵循以下精确步骤：
1. 首先，调用 `run_intent` 工具，`intent` 设为 `get_current_date_and_time`，`parameters` 设为 `{}`，获取用户的本地日期、时间和当前星期几。
2. 读取事件前，在回复中明确计算用户请求的确切目标日期。弄清楚：
- 今天的确切日期和星期几。
- 用户请求的目标日期或相对时间（例如"明天"、"本周五"、"5月15日"）。
- 最终计算的目标日期，格式为 YYYY-MM-DD。
3. 计算出正确日期后，调用 `run_intent` 工具，传入以下精确参数：
- `intent`: read_calendar_events
- `parameters`: JSON 字符串，包含以下字段：
   - `date`: 要读取事件的目标日期，格式为 YYYY-MM-DD。字符串。
4. 解释返回的日历事件JSON列表，向用户提供清晰、友好的回复，详细说明他们的日程安排。
