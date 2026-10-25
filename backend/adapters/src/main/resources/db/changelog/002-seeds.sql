INSERT INTO practitioner(id,display_name) VALUES(1,'Medina') ON CONFLICT DO NOTHING;
INSERT INTO scheduling_resource(id,practitioner_id) VALUES(1,1) ON CONFLICT DO NOTHING;
INSERT INTO policy_version(version,document) VALUES(1,'{"version":1,"gridMinutes":30,"dailyCapacity":3,"minimumNotice":86400.000000000,"pendingLifetime":86400.000000000,"expiryBuffer":3600.000000000,"customerCutoff":86400.000000000}') ON CONFLICT DO NOTHING;
INSERT INTO business_settings(id,policy_version,calendar) VALUES(1,1,'{"weekly":{"MONDAY":[{"startMinute":540,"endMinute":1200}],"TUESDAY":[{"startMinute":540,"endMinute":1200}],"WEDNESDAY":[{"startMinute":540,"endMinute":1200}],"THURSDAY":[{"startMinute":540,"endMinute":1200}],"FRIDAY":[{"startMinute":540,"endMinute":1200}],"SATURDAY":[{"startMinute":540,"endMinute":1200}],"SUNDAY":[{"startMinute":540,"endMinute":1200}]},"exceptions":{},"blackouts":[],"version":1}') ON CONFLICT DO NOTHING;
INSERT INTO service(id,version,name,duration_minutes,price_minor,currency,active) VALUES
('10000000-0000-0000-0000-000000000001',1,'Karmic reading',30,1500,'EUR',true),
('10000000-0000-0000-0000-000000000002',1,'Transit reading',60,3000,'EUR',true) ON CONFLICT DO NOTHING;
INSERT INTO operation_control(id) VALUES(1) ON CONFLICT DO NOTHING;
