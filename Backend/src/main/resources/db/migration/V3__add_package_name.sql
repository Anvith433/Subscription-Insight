-- Add the package_name column to the subscriptions table
ALTER TABLE subscriptions 
ADD COLUMN package_name VARCHAR(255) AFTER provider_name;

-- Optional: Add a unique constraint to ensure a user doesn't have 
-- the same app package added twice
ALTER TABLE subscriptions 
ADD CONSTRAINT uk_user_package UNIQUE (user_id, package_name);

-- Update existing data so the sync works immediately for your seed users
UPDATE subscriptions SET package_name = 'com.spotify.music' WHERE provider_name = 'Spotify';
UPDATE subscriptions SET package_name = 'com.google.android.youtube' WHERE provider_name = 'YouTube Premium';
UPDATE subscriptions SET package_name = 'com.netflix.mediaclient' WHERE provider_name = 'Netflix';