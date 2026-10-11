-- DBeaver CE 26.2.2 (org.jkiss.dbeaver.ext.postgresql), pgjdbc 42.7.13, server reported as PostgreSQL 16.0.
-- Source paths are relative to plugins/org.jkiss.dbeaver.ext.postgresql/src/org/jkiss/dbeaver/ext/postgresql/.
-- JDBC ? parameters are replaced by literals or subselects for database ns, schema public, table t.
-- DBeaver opens two connections (main + separate metadata connection, the default), so the two per-connection
-- context queries appear twice. pgjdbc sends extra_float_digits, application_name, DateStyle, client_encoding and TimeZone
-- as startup parameters, not SQL. Auto-commit is on by default, so no isolation-level query or COMMIT is sent.
-- [connect, main connection] read default search_path (model/PostgreExecutionContext.java:refreshDefaults)
SELECT reset_val FROM pg_settings WHERE name = 'search_path'
-----
-- [connect, main connection] current schema and user (model/PostgreExecutionContext.java:refreshDefaults)
SELECT current_schema(),session_user
-----
-- [connect, main connection] schema list (pre-cached by setActiveSchema; this is also the navigator schema list, no filters) (model/PostgreDatabase.java:SchemaCache.prepareLookupStatement)
SELECT n.oid,n.*,d.description FROM pg_catalog.pg_namespace n
LEFT OUTER JOIN pg_catalog.pg_description d ON d.objoid=n.oid AND d.objsubid=0 AND d.classoid='pg_namespace'::regclass
 ORDER BY nspname
-----
-- [connect, separate metadata connection] read default search_path (model/PostgreExecutionContext.java:refreshDefaults)
SELECT reset_val FROM pg_settings WHERE name = 'search_path'
-----
-- [connect, separate metadata connection] current schema and user (model/PostgreExecutionContext.java:refreshDefaults)
SELECT current_schema(),session_user
-----
-- [connect] database info for the connected database (this is the whole 'database list' when 'Show all databases' is off) (model/PostgreDatabase.java:readDatabaseInfo)
SELECT db.oid,db.* FROM pg_catalog.pg_database db WHERE datname='ns'
-----
-- [connect] preload setting standard_conforming_strings (model/PostgreDataSource.java:SettingCache.prepareLookupStatement (from initializeRemoteInstance))
select * from pg_catalog.pg_settings where name='standard_conforming_strings'
-----
-- [connect] SQL keywords, issued by pgjdbc 42.7.13 PgDatabaseMetaData.getSQLKeywords() on DBeaver's request (org.jkiss.dbeaver.model.jdbc JDBCSQLDialect.java:loadDriverKeywords)
select string_agg(word, ',') from pg_catalog.pg_get_keywords() where word <> ALL ('{a,abs,absolute,action,ada,add,admin,after,all,allocate,alter,always,and,any,are,array,as,asc,asensitive,assertion,assignment,asymmetric,at,atomic,attribute,attributes,authorization,avg,before,begin,bernoulli,between,bigint,binary,blob,boolean,both,breadth,by,c,call,called,cardinality,cascade,cascaded,case,cast,catalog,catalog_name,ceil,ceiling,chain,char,char_length,character,character_length,character_set_catalog,character_set_name,character_set_schema,characteristics,characters,check,checked,class_origin,clob,close,coalesce,cobol,code_units,collate,collation,collation_catalog,collation_name,collation_schema,collect,column,column_name,command_function,command_function_code,commit,committed,condition,condition_number,connect,connection_name,constraint,constraint_catalog,constraint_name,constraint_schema,constraints,constructors,contains,continue,convert,corr,corresponding,count,covar_pop,covar_samp,create,cross,cube,cume_dist,current,current_collation,current_date,current_default_transform_group,current_path,current_role,current_time,current_timestamp,current_transform_group_for_type,current_user,cursor,cursor_name,cycle,data,date,datetime_interval_code,datetime_interval_precision,day,deallocate,dec,decimal,declare,default,defaults,deferrable,deferred,defined,definer,degree,delete,dense_rank,depth,deref,derived,desc,describe,descriptor,deterministic,diagnostics,disconnect,dispatch,distinct,domain,double,drop,dynamic,dynamic_function,dynamic_function_code,each,element,else,end,end-exec,equals,escape,every,except,exception,exclude,excluding,exec,execute,exists,exp,external,extract,false,fetch,filter,final,first,float,floor,following,for,foreign,fortran,found,free,from,full,function,fusion,g,general,get,global,go,goto,grant,granted,group,grouping,having,hierarchy,hold,hour,identity,immediate,implementation,in,including,increment,indicator,initially,inner,inout,input,insensitive,insert,instance,instantiable,int,integer,intersect,intersection,interval,into,invoker,is,isolation,join,k,key,key_member,key_type,language,large,last,lateral,leading,left,length,level,like,ln,local,localtime,localtimestamp,locator,lower,m,map,match,matched,max,maxvalue,member,merge,message_length,message_octet_length,message_text,method,min,minute,minvalue,mod,modifies,module,month,more,multiset,mumps,name,names,national,natural,nchar,nclob,nesting,new,next,no,none,,nullable,nullif,nulls,number,numeric,object,octet_length,octets,of,old,on,only,open,option,options,or,order,ordering,ordinality,others,out,outer,output,over,overlaps,overlay,overriding,pad,parameter,parameter_mode,parameter_name,parameter_ordinal_position,parameter_specific_catalog,parameter_specific_name,parameter_specific_schema,partial,partition,pascal,path,percent_rank,percentile_cont,percentile_disc,placing,pli,position,power,preceding,precision,prepare,preserve,primary,prior,privileges,procedure,public,range,rank,read,reads,real,recursive,ref,references,referencing,regr_avgx,regr_avgy,regr_count,regr_intercept,regr_r2,regr_slope,regr_sxx,regr_sxy,regr_syy,relative,release,repeatable,restart,result,return,returned_cardinality,returned_length,returned_octet_length,returned_sqlstate,returns,revoke,right,role,rollback,rollup,routine,routine_catalog,routine_name,routine_schema,row,row_count,row_number,rows,savepoint,scale,schema,schema_name,scope_catalog,scope_name,scope_schema,scroll,search,second,section,security,select,self,sensitive,sequence,serializable,server_name,session,session_user,set,sets,similar,simple,size,smallint,some,source,space,specific,specific_name,specifictype,sql,sqlexception,sqlstate,sqlwarning,sqrt,start,state,statement,static,stddev_pop,stddev_samp,structure,style,subclass_origin,submultiset,substring,sum,symmetric,system,system_user,table,table_name,tablesample,temporary,then,ties,time,timestamp,timezone_hour,timezone_minute,to,top_level_count,trailing,transaction,transaction_active,transactions_committed,transactions_rolled_back,transform,transforms,translate,translation,treat,trigger,trigger_catalog,trigger_name,trigger_schema,trim,true,type,uescape,unbounded,uncommitted,under,union,unique,unknown,unnamed,unnest,update,upper,usage,user,user_defined_type_catalog,user_defined_type_code,user_defined_type_name,user_defined_type_schema,using,value,values,var_pop,var_samp,varchar,varying,view,when,whenever,where,width_bucket,window,with,within,without,work,write,year,zone}'::text[])
-----
-- [connect] server version string (model/PostgreDataSource.java:initialize)
SELECT version()
-----
-- [connect] probe: pg_enum exists (server >= 12) (model/PostgreDataSource.java:initialize -> PostgreUtils.isMetaObjectExists)
SELECT * FROM pg_catalog.pg_enum WHERE 1<>1 LIMIT 1
-----
-- [connect] probe: pg_class.reltype exists (model/PostgreDataSource.java:initialize -> PostgreUtils.isMetaObjectExists)
SELECT reltype FROM pg_catalog.pg_class WHERE 1<>1 LIMIT 1
-----
-- [connect] data type cache (read all data types = off; typcategory assumed for server >= 10, so no probe) (model/PostgreDatabase.java:cacheDataTypes)
SELECT t.oid,t.*,c.relkind,format_type(nullif(t.typbasetype, 0), t.typtypmod) as base_type_name, d.description
FROM pg_catalog.pg_type t
LEFT OUTER JOIN pg_catalog.pg_type et ON et.oid=t.typelem 
LEFT OUTER JOIN pg_catalog.pg_class c ON c.oid=t.typrelid
LEFT OUTER JOIN pg_catalog.pg_description d ON t.oid=d.objoid
WHERE t.typname IS NOT NULL
AND (c.relkind IS NULL OR c.relkind = 'c') AND (et.typcategory IS NULL OR et.typcategory <> 'C')
-----
-- [connect] enum values cache (after data types) (model/PostgreDatabase.java:EnumValueCache.prepareObjectsStatement (initEnumTypesCache))
SELECT * FROM pg_catalog.pg_enum
-----
-- [navigator: expand schema public > Tables] table list (model/PostgreSchema.java:TableCache.prepareLookupStatement)
SELECT c.oid,c.*,d.description,pg_catalog.pg_get_expr(c.relpartbound, c.oid) as partition_expr,  pg_catalog.pg_get_partkeydef(c.oid) as partition_key 
FROM pg_catalog.pg_class c
LEFT OUTER JOIN pg_catalog.pg_description d ON d.objoid=c.oid AND d.objsubid=0 AND d.classoid='pg_class'::regclass
WHERE c.relnamespace=(SELECT oid FROM pg_catalog.pg_namespace WHERE nspname='public') AND c.relkind not in ('i','I','c')
-----
-- [navigator: Tables folder, object size statistics, only if navigator statistics display is on] relation sizes for the schema (model/PostgreSchema.java:collectObjectStatistics)
select c.oid,pg_catalog.pg_total_relation_size(c.oid) as total_rel_size,pg_catalog.pg_relation_size(c.oid) as rel_size
FROM pg_class c
WHERE c.relnamespace=(SELECT oid FROM pg_catalog.pg_namespace WHERE nspname='public')
-----
-- [navigator: expand orders > Columns] columns of one table (model/PostgreSchema.java:TableCache.prepareChildrenStatement)
SELECT c.relname,a.*,pg_catalog.pg_get_expr(ad.adbin, ad.adrelid, true) as def_value,dsc.description,dep.objid
FROM pg_catalog.pg_attribute a
INNER JOIN pg_catalog.pg_class c ON (a.attrelid=c.oid)
LEFT OUTER JOIN pg_catalog.pg_attrdef ad ON (a.attrelid=ad.adrelid AND a.attnum = ad.adnum)
LEFT OUTER JOIN pg_catalog.pg_description dsc ON (c.oid=dsc.objoid AND a.attnum = dsc.objsubid)
LEFT OUTER JOIN pg_depend dep on dep.refobjid = a.attrelid AND dep.deptype = 'i' and dep.refobjsubid = a.attnum and dep.classid = dep.refclassid
WHERE NOT a.attisdropped AND c.relkind not in ('i','I','c') AND c.oid=(SELECT oid FROM pg_catalog.pg_class WHERE relname='t' AND relnamespace=(SELECT oid FROM pg_catalog.pg_namespace WHERE nspname='public'))
ORDER BY a.attnum
-----
-- [navigator: expand orders > Constraints] constraints of one table; constraint columns come from conkey resolved against the cached columns (no extra query) (model/PostgreSchema.java:ConstraintCache.prepareObjectsStatement)
SELECT c.oid,c.*,t.relname as tabrelname,rt.relnamespace as refnamespace,d.description, case when c.contype='c' then "substring"(pg_get_constraintdef(c.oid), 7) else null end consrc_copy
FROM pg_catalog.pg_constraint c
INNER JOIN pg_catalog.pg_class t ON t.oid=c.conrelid
LEFT OUTER JOIN pg_catalog.pg_class rt ON rt.oid=c.confrelid
LEFT OUTER JOIN pg_catalog.pg_description d ON d.objoid=c.oid AND d.objsubid=0 AND d.classoid='pg_constraint'::regclass
WHERE c.conrelid=(SELECT oid FROM pg_catalog.pg_class WHERE relname='t' AND relnamespace=(SELECT oid FROM pg_catalog.pg_namespace WHERE nspname='public'))
ORDER BY c.oid
-----
-- [navigator: expand orders > Foreign Keys] super-table inheritance (FKs themselves come from the constraint cache above) (model/PostgreTable.java:initSuperTables (via getAssociations/getSuperInheritance))
SELECT i.*,c.relnamespace FROM pg_catalog.pg_inherits i,pg_catalog.pg_class c WHERE i.inhrelid=(SELECT oid FROM pg_catalog.pg_class WHERE relname='t' AND relnamespace=(SELECT oid FROM pg_catalog.pg_namespace WHERE nspname='public')) AND c.oid=i.inhparent ORDER BY i.inhseqno
-----
-- [navigator: expand orders > Indexes] indexes of one table; index columns come from indkey resolved against the cached columns (model/PostgreSchema.java:IndexCache.prepareObjectsStatement)
SELECT i.*,i.indkey as keys,c.relname,c.relnamespace,c.relam,c.reltablespace,tc.relname as tabrelname,dsc.description,pg_catalog.pg_get_expr(i.indpred, i.indrelid) as pred_expr,pg_catalog.pg_get_expr(i.indexprs, i.indrelid, true) as expr,pg_catalog.pg_relation_size(i.indexrelid) as index_rel_size,pg_catalog.pg_stat_get_numscans(i.indexrelid) as index_num_scans
FROM pg_catalog.pg_index i
INNER JOIN pg_catalog.pg_class c ON c.oid=i.indexrelid
INNER JOIN pg_catalog.pg_class tc ON tc.oid=i.indrelid
LEFT OUTER JOIN pg_catalog.pg_description dsc ON i.indexrelid=dsc.objoid
WHERE  i.indrelid=(SELECT oid FROM pg_catalog.pg_class WHERE relname='t' AND relnamespace=(SELECT oid FROM pg_catalog.pg_namespace WHERE nspname='public')) ORDER BY tabrelname, c.relname
-----
-- [navigator: Indexes, while reading index columns] access methods cache (model/PostgreDatabase.java:AccessMethodCache.prepareObjectsStatement (PostgreIndex.getAccessMethod))
SELECT am.oid,am.* FROM pg_catalog.pg_am am 
ORDER BY am.oid
-----
-- [navigator: expand an index > its columns, Operator class property] operator classes cache (model/PostgreAccessMethod.java:OperatorClassCache.prepareObjectsStatement (PostgreIndexColumn.getOperatorClass))
SELECT oc.oid,oc.* FROM pg_catalog.pg_opclass oc 
ORDER BY oc.oid
-----
-- [table editor Properties: Owner] roles cache (model/PostgreDatabase.java:RoleCache.prepareObjectsStatement (PostgreTableBase.getOwner))
SELECT a.oid,a.*,pd.description FROM pg_catalog.pg_roles a 
left join pg_catalog.pg_shdescription pd on a.oid = pd.objoid
ORDER BY a.rolname
-----
-- [table editor Properties: Tablespace] tablespaces cache (table has reltablespace=0, so the database default is looked up) (model/PostgreDatabase.java:TablespaceCache.prepareObjectsStatement (PostgreTable.getTablespace))
SELECT t.oid,t.*,pg_tablespace_location(t.oid) loc
FROM pg_catalog.pg_tablespace t 
ORDER BY t.oid
-----
-- [table editor Properties: Statistics, skipped if schema statistics were already collected] table size (model/PostgreTableReal.java:readTableStatistics)
select pg_catalog.pg_total_relation_size((SELECT oid FROM pg_catalog.pg_class WHERE relname='t' AND relnamespace=(SELECT oid FROM pg_catalog.pg_namespace WHERE nspname='public'))) as total_rel_size,pg_catalog.pg_relation_size((SELECT oid FROM pg_catalog.pg_class WHERE relname='t' AND relnamespace=(SELECT oid FROM pg_catalog.pg_namespace WHERE nspname='public'))) as rel_size
-----
-- [table editor Columns tab: Collation column] collations cache (only when some column has attcollation > 0) (model/PostgreDatabase.java:CollationCache.prepareObjectsStatement (PostgreAttribute.getCollation))
SELECT c.oid,c.* FROM pg_catalog.pg_collation c 
ORDER BY c.oid
-----
-- [table editor References tab] schemas holding FKs that point to this table (model/PostgreTable.java:getReferences)
SELECT DISTINCT connamespace FROM pg_catalog.pg_constraint WHERE confrelid=(SELECT oid FROM pg_catalog.pg_class WHERE relname='t' AND relnamespace=(SELECT oid FROM pg_catalog.pg_namespace WHERE nspname='public'))
-----
-- [table editor Triggers tab / DDL] triggers (model/PostgreTableReal.java:TriggerCache.prepareLookupStatement)
SELECT x.oid,x.*,p.pronamespace as func_schema_id,d.description
FROM pg_catalog.pg_trigger x
LEFT OUTER JOIN pg_catalog.pg_proc p ON p.oid=x.tgfoid 
LEFT OUTER JOIN pg_catalog.pg_description d ON d.objoid=x.oid AND d.objsubid=0 
WHERE x.tgrelid = (SELECT oid FROM pg_catalog.pg_class WHERE relname='t' AND relnamespace=(SELECT oid FROM pg_catalog.pg_namespace WHERE nspname='public')) AND NOT x.tgisinternal
-----
-- [table editor Rules tab / DDL] rules (DBeaver inlines the oid as a number, not a bind parameter) (model/PostgreTableReal.java:RuleCache.prepareObjectsStatement)
SELECT r.oid,r.*, pg_get_ruledef(r.oid) AS definition
FROM pg_rewrite r
WHERE r.ev_class=(SELECT oid FROM pg_catalog.pg_class WHERE relname='t' AND relnamespace=(SELECT oid FROM pg_catalog.pg_namespace WHERE nspname='public')) AND r.rulename <> '_RETURN'::name
-----
-- [table editor Policies tab / DDL] row level security policies (model/PostgreTable.java:PolicyCache.prepareObjectsStatement)
select * from pg_catalog.pg_policies where schemaname='public' and tablename='t'
-----
-- [table editor DDL tab] primary key definition (one per constraint; also run for unique/check/FK constraints) (model/PostgreTableConstraintBase.java:getObjectDefinitionText)
SELECT pg_catalog.pg_get_constraintdef((SELECT oid FROM pg_catalog.pg_constraint WHERE conrelid=(SELECT oid FROM pg_catalog.pg_class WHERE relname='t' AND relnamespace=(SELECT oid FROM pg_catalog.pg_namespace WHERE nspname='public')) AND contype='p'))
-----
-- [table editor DDL tab] index definition, only for indexes that are not the primary key index (example picks one) (model/PostgreIndex.java:getObjectDefinitionText)
SELECT pg_catalog.pg_get_indexdef((SELECT indexrelid FROM pg_catalog.pg_index WHERE indrelid=(SELECT oid FROM pg_catalog.pg_class WHERE relname='t' AND relnamespace=(SELECT oid FROM pg_catalog.pg_namespace WHERE nspname='public')) AND NOT indisprimary LIMIT 1))
-----
-- [table editor Dependencies tab] objects depending on the table (model/PostgreDependency.java:readDependencies)
SELECT DISTINCT dep.deptype, dep.classid, dep.objid, cl.relkind, attr.attname,pg_get_expr(ad.adbin, ad.adrelid) adefval,
    CASE WHEN cl.relkind IS NOT NULL THEN cl.relkind::text || COALESCE(dep.objsubid::text, '')::text
        WHEN tg.oid IS NOT NULL THEN 'T'::text
        WHEN ty.oid IS NOT NULL THEN 'y'::text
        WHEN ns.oid IS NOT NULL THEN 'n'::text
        WHEN pr.oid IS NOT NULL THEN 'p'::text
        WHEN la.oid IS NOT NULL THEN 'l'::text
        WHEN rw.oid IS NOT NULL THEN 'R'::text
        WHEN co.oid IS NOT NULL THEN 'C'::text || contype::text
        WHEN ad.oid IS NOT NULL THEN 'A'::text
        ELSE ''
    END AS type,
    COALESCE(coc.relname, clrw.relname, tgr.relname) AS ownertable,
    CASE WHEN cl.relname IS NOT NULL AND att.attname IS NOT NULL THEN cl.relname || '.' || att.attname
    ELSE COALESCE(cl.relname, co.conname, pr.proname, tg.tgname, ty.typname, la.lanname, rw.rulename, ns.nspname)
    END AS refname,
    COALESCE(nsc.nspname, nso.nspname, nsp.nspname, nst.nspname, nsrw.nspname, tgrn.nspname) AS nspname
FROM pg_depend dep
LEFT JOIN pg_class cl ON dep.objid=cl.oid
LEFT JOIN pg_attribute att ON dep.objid=att.attrelid AND dep.objsubid=att.attnum
LEFT JOIN pg_namespace nsc ON cl.relnamespace=nsc.oid
LEFT JOIN pg_proc pr ON dep.objid=pr.oid
LEFT JOIN pg_namespace nsp ON pr.pronamespace=nsp.oid
LEFT JOIN pg_trigger tg ON dep.objid=tg.oid
LEFT JOIN pg_class tgr ON tg.tgrelid=tgr.oid
LEFT JOIN pg_namespace tgrn ON tgr.relnamespace=tgrn.oid
LEFT JOIN pg_type ty ON dep.objid=ty.oid
LEFT JOIN pg_namespace nst ON ty.typnamespace=nst.oid
LEFT JOIN pg_constraint co ON dep.objid=co.oid
LEFT JOIN pg_class coc ON co.conrelid=coc.oid
LEFT JOIN pg_namespace nso ON co.connamespace=nso.oid
LEFT JOIN pg_rewrite rw ON dep.objid=rw.oid
LEFT JOIN pg_class clrw ON clrw.oid=rw.ev_class
LEFT JOIN pg_namespace nsrw ON clrw.relnamespace=nsrw.oid
LEFT JOIN pg_language la ON dep.objid=la.oid
LEFT JOIN pg_namespace ns ON dep.objid=ns.oid
LEFT JOIN pg_attrdef ad ON ad.oid=dep.objid
LEFT JOIN pg_attribute attr ON attr.attrelid=ad.adrelid and attr.attnum=ad.adnum
WHERE dep.refobjid=(SELECT oid FROM pg_catalog.pg_class WHERE relname='t' AND relnamespace=(SELECT oid FROM pg_catalog.pg_namespace WHERE nspname='public'))
ORDER BY type
-----
-- [NOT sent with the default settings; only when 'Show all databases' is on] database list, connection database = demo (model/PostgreDataSource.java:prepareReadDatabaseListStatement)
SELECT db.oid,db.* FROM pg_catalog.pg_database db WHERE 1 = 1 AND datallowconn AND NOT datistemplate OR db.datname ='demo'
ORDER BY db.datname
