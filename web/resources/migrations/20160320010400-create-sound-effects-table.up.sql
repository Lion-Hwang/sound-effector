CREATE TABLE sound_effects (
  id         SERIAL PRIMARY KEY,
  title      VARCHAR   NOT NULL,
  url        VARCHAR   NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
