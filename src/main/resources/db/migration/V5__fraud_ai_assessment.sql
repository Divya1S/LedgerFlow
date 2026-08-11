-- AI fraud-analyst copilot: stores the structured assessment an LLM
-- produces for REVIEW/REJECTED verdicts. Deliberately additive only: the
-- verdict pipeline works identically whether or not an assessment ever
-- arrives, and nothing financial can depend on these columns.

ALTER TABLE fraud_decisions ADD COLUMN ai_assessment JSONB;
ALTER TABLE fraud_decisions ADD COLUMN ai_model TEXT;
ALTER TABLE fraud_decisions ADD COLUMN ai_assessed_at TIMESTAMPTZ;
