DO $$
DECLARE
    configured_password TEXT := '${meterlyAppPassword}';
BEGIN
    IF length(configured_password) < 32 THEN
        RAISE EXCEPTION 'meterlyAppPassword must contain at least 32 characters';
    END IF;

    EXECUTE format('ALTER ROLE meterly_app PASSWORD %L', configured_password);
END
$$;
