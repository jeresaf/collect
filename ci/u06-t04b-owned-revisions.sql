-- U06-T04B-01: disposable CI-only data. Never apply to an existing database.
-- Fixed synthetic IDs allow Android instrumentation to assert exact ownership
-- and revision flags against a real PostgreSQL/Flyway-backed server.
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO projects (id,name,owner_id,created_by_id)
SELECT '81000000-0000-4000-8000-000000000001', 'U06 disposable project', id, id
FROM users WHERE username = 'admin';

INSERT INTO xforms (id,title,xmlns,project_id,created_by_id,is_published)
SELECT '81000000-0000-4000-8000-000000000002','U06 synthetic household',
       'u06-synthetic-household','81000000-0000-4000-8000-000000000001', id, TRUE
FROM users WHERE username='admin';

INSERT INTO form_versions (id,form_id,version,xml_content,xmlns,created_by_id,is_published,is_latest)
SELECT '81000000-0000-4000-8000-000000000003',
       '81000000-0000-4000-8000-000000000002','1',
       '<data id="u06-synthetic-household" version="1"><household_name/></data>',
       'u06-synthetic-household',id,TRUE,FALSE
FROM users WHERE username='admin';

INSERT INTO form_versions (id,form_id,version,xml_content,xmlns,created_by_id,is_published,is_latest)
SELECT '81000000-0000-4000-8000-000000000004',
       '81000000-0000-4000-8000-000000000002','2',
       '<data id="u06-synthetic-household" version="2"><household_name/></data>',
       'u06-synthetic-household',id,TRUE,TRUE
FROM users WHERE username='admin';

INSERT INTO app_users(id,username,email,password_hash,full_name,is_active,is_verified)
VALUES ('81000000-0000-4000-8000-000000000005','u06-connected-owner',
        'u06-connected-owner@example.test',crypt('u06-disposable-only',gen_salt('bf',10)),
        'Synthetic U06 enumerator',TRUE,TRUE);

INSERT INTO app_user_projects(id,app_user_id,project_id,form_id,status)
VALUES ('81000000-0000-4000-8000-000000000006',
        '81000000-0000-4000-8000-000000000005',
        '81000000-0000-4000-8000-000000000001',
        '81000000-0000-4000-8000-000000000002','ACTIVE');

INSERT INTO submissions(id,form_id,form_version_id,instance_id,xml_content,submitted_at,submitted_by_id)
VALUES
('81000000-0000-4000-8000-000000000007',
 '81000000-0000-4000-8000-000000000002',
 '81000000-0000-4000-8000-000000000003',
 'uuid:81000000-0000-4000-8000-000000000017',
 '<data id="u06-synthetic-household" version="1"><household_name>Synthetic V1</household_name></data>',
 '2026-11-20 10:00:00+00','81000000-0000-4000-8000-000000000005'),
('81000000-0000-4000-8000-000000000008',
 '81000000-0000-4000-8000-000000000002',
 '81000000-0000-4000-8000-000000000004',
 'uuid:81000000-0000-4000-8000-000000000018',
 '<data id="u06-synthetic-household" version="2"><household_name>Synthetic V2</household_name></data>',
 '2026-11-21 10:00:00+00','81000000-0000-4000-8000-000000000005');

INSERT INTO submission_versions(submission_id,version_number,xml_content,instance_id,submitted_by_id,submitted_at,source_form_version_id)
VALUES ('81000000-0000-4000-8000-000000000007',1,
 '<data id="u06-synthetic-household" version="1"><household_name>Synthetic V1</household_name></data>',
 'uuid:81000000-0000-4000-8000-000000000017',
 '81000000-0000-4000-8000-000000000005','2026-11-20 10:00:00+00',
 '81000000-0000-4000-8000-000000000003');
-- Independent conflict fixture: separate project, exact same synthetic mobile owner.
-- A work item was issued before the original source acquired a revision
-- archive. It must remain visible only as a conflict, never editable.
INSERT INTO projects (id,name,owner_id,created_by_id)
SELECT '82000000-0000-4000-8000-000000000001','U06 stale recovery project',id,id
FROM users WHERE username='admin';

INSERT INTO xforms(id,title,xmlns,project_id,created_by_id,is_published)
SELECT '82000000-0000-4000-8000-000000000002','U06 stale form',
       'u06-stale-household','82000000-0000-4000-8000-000000000001',id,TRUE
FROM users WHERE username='admin';

INSERT INTO form_versions(id,form_id,version,xml_content,xmlns,created_by_id,is_published,is_latest)
SELECT '82000000-0000-4000-8000-000000000003',
       '82000000-0000-4000-8000-000000000002','1',
       '<data id="u06-stale-household" version="1"><household_name/></data>',
       'u06-stale-household',id,TRUE,FALSE
FROM users WHERE username='admin';

INSERT INTO form_versions(id,form_id,version,xml_content,xmlns,created_by_id,is_published,is_latest)
SELECT '82000000-0000-4000-8000-000000000004',
       '82000000-0000-4000-8000-000000000002','2',
       '<data id="u06-stale-household" version="2"><household_name/></data>',
       'u06-stale-household',id,TRUE,TRUE
FROM users WHERE username='admin';

INSERT INTO app_user_projects(id,app_user_id,project_id,form_id,status)
VALUES ('82000000-0000-4000-8000-000000000006',
        '81000000-0000-4000-8000-000000000005',
        '82000000-0000-4000-8000-000000000001',
        '82000000-0000-4000-8000-000000000002','ACTIVE');

INSERT INTO submissions(id,form_id,form_version_id,instance_id,xml_content,submitted_at,submitted_by_id)
VALUES ('82000000-0000-4000-8000-000000000007',
        '82000000-0000-4000-8000-000000000002',
        '82000000-0000-4000-8000-000000000003',
        'uuid:82000000-0000-4000-8000-000000000017',
        '<data id="u06-stale-household" version="1"><household_name>PRIVATE_STALE_SOURCE</household_name></data>',
        '2026-11-19 10:00:00+00','81000000-0000-4000-8000-000000000005');

INSERT INTO record_work_items(id,project_id,source_submission_id,source_form_version_id,
        source_instance_id,target_form_version_id,assigned_app_user_id,work_type,state,
        client_request_id,created_at)
VALUES ('82000000-0000-4000-8000-000000000009',
        '82000000-0000-4000-8000-000000000001',
        '82000000-0000-4000-8000-000000000007',
        '82000000-0000-4000-8000-000000000003',
        'uuid:82000000-0000-4000-8000-000000000017',
        '82000000-0000-4000-8000-000000000004',
        '81000000-0000-4000-8000-000000000005',
        'AMENDMENT','READY','u06-issued-before-intervening-amendment',
        '2026-11-20 10:00:00+00');

INSERT INTO submission_versions(submission_id,version_number,xml_content,instance_id,
        submitted_by_id,submitted_at,created_at,source_form_version_id)
VALUES ('82000000-0000-4000-8000-000000000007',1,
        '<data id="u06-stale-household" version="1"><household_name>PRIVATE_STALE_SOURCE</household_name></data>',
        'uuid:82000000-0000-4000-8000-000000000017',
        '81000000-0000-4000-8000-000000000005',
        '2026-11-19 10:00:00+00','2026-11-21 10:00:00+00',
        '82000000-0000-4000-8000-000000000003');
COMMIT;
