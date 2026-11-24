-- CI-only target added after the earlier V1/V2 and restart gates.
-- Preserve prior exact target catalogs and existing submissions.
BEGIN;
INSERT INTO form_versions (id,form_id,version,xml_content,xmlns,created_by_id,is_published,is_latest)
SELECT '81000000-0000-4000-8000-000000000019',
       '81000000-0000-4000-8000-000000000002','3',
       $xform$<h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns="http://www.w3.org/2002/xforms" xmlns:jr="http://openrosa.org/javarosa"><h:head><h:title>U06 synthetic conditional visit</h:title><model><instance><data id="u06-synthetic-household" version="3"><gate/><count/><total/><meta><instanceID/></meta></data></instance><bind nodeset="/data/gate" type="string" required="true()" constraint=". = 'yes' or . = 'no'"/><bind nodeset="/data/count" type="int" relevant="/data/gate = 'yes'" required="true()" constraint=". &gt;= 1 and . &lt;= 10"/><bind nodeset="/data/total" type="int" calculate="if(/data/gate = 'yes', /data/count * 2, 0)" readonly="true()"/></model></h:head><h:body><input ref="/data/gate"><label>Count applicable?</label></input><input ref="/data/count"><label>Count from 1 to 10</label></input><input ref="/data/total"><label>Calculated total</label></input></h:body></h:html>$xform$,
       'u06-synthetic-household',id,TRUE,FALSE
FROM users WHERE username='admin';
COMMIT;
