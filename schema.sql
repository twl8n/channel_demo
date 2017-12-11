-- cat schema.sql | sqlite3 logger.db
create table log (
        id integer primary key autoincrement,
        date text,
	msg text
);

