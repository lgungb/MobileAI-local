---
name: create-calendar-event
description: 创建日历事件。
---

# 创建日历事件

## 使用说明

要安排事件，必须遵循以下精确步骤：
1. 首先，调用 `run_intent` 工具，`intent` 设为 `get_current_date_and_time`，`parameters` 设为 `{}`，获取用户的本地日期、时间和当前星期几。
2. 创建事件前，必须在回复中明确计算确切日期。写出：
    - 今天的确切日期和星期几。
    - 用户请求的目标日期或相对时间（例如"明天"、"本周五"）。
    - 需要在今天日期上加上的确切天数。
    - 最终计算的日期，确保如果加上的天数超过当前月的天数，正确滚动到下一个月或年。
3. 计算出正确日期后，调用 `run_intent` 工具，传入以下精确参数：
    - `intent`: create_calendar_event
    - `parameters`: JSON 字符串，包含以下字段：
        - `title`: 事件标题。字符串。
        - `description`: 事件描述。字符串。
        - `begin_time`: 事件开始时间，格式为 YYYY-MM-DDTHH:MM:SS。字符串。
        - `end_time`: 事件结束时间，格式为 YYYY-MM-DDTHH:MM:SS。字符串。
