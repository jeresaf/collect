-- Disposable mixed V5 target, introduced after all previous gates.
BEGIN;
INSERT INTO form_versions (id,form_id,version,xml_content,xmlns,created_by_id,is_published,is_latest)
SELECT '81000000-0000-4000-8000-000000000021',
       '81000000-0000-4000-8000-000000000002','5',
       $xform$<h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns="http://www.w3.org/2002/xforms" xmlns:jr="http://openrosa.org/javarosa"><h:head><h:title>U06 synthetic mixed visit</h:title><model><instance><data id="u06-synthetic-household" version="5"><household_name/><photo/><members jr:template=""><name/><photo/></members><meta><instanceID/></meta></data></instance><bind nodeset="/data/household_name" type="string"/><bind nodeset="/data/photo" type="binary"/><bind nodeset="/data/members/name" type="string"/><bind nodeset="/data/members/photo" type="binary"/></model></h:head><h:body><input ref="/data/household_name"><label>Household name</label></input><upload ref="/data/photo" mediatype="image/*"><label>Household photo</label></upload><repeat nodeset="/data/members"><label>Member</label><input ref="/data/members/name"><label>Name</label></input><upload ref="/data/members/photo" mediatype="image/*"><label>Photo</label></upload></repeat></h:body></h:html>$xform$,
       'u06-synthetic-household',id,TRUE,FALSE
FROM users WHERE username='admin';
COMMIT;
