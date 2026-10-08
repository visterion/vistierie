ALTER TABLE vistierie.llm_calls
  ADD COLUMN served_model TEXT,
  ADD COLUMN quota_five_hour_util NUMERIC(5,4),
  ADD COLUMN quota_seven_day_util NUMERIC(5,4),
  ADD COLUMN quota_status TEXT;
