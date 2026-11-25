-- Disposable published V4 target, only after the earlier gates.
BEGIN;
INSERT INTO form_versions (id,form_id,version,xml_content,xmlns,created_by_id,is_published,is_latest)
SELECT '81000000-0000-4000-8000-000000000020',
       '81000000-0000-4000-8000-000000000002','4',
       $xform$<h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns="http://www.w3.org/2002/xforms" xmlns:jr="http://openrosa.org/javarosa"><h:head><h:title>U06 synthetic member images</h:title><model><instance><data id="u06-synthetic-household" version="4"><members jr:template=""><name/><photo/></members><meta><instanceID/></meta></data></instance><bind nodeset="/data/members/name" type="string"/><bind nodeset="/data/members/photo" type="binary"/></model></h:head><h:body><repeat nodeset="/data/members"><label>Member</label><input ref="/data/members/name"><label>Name</label></input><upload ref="/data/members/photo" mediatype="image/*"><label>Photo</label></upload></repeat></h:body></h:html>$xform$,
       'u06-synthetic-household',id,TRUE,FALSE
FROM users WHERE username='admin';
COMMIT;
