import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const sourceRoot = path.resolve('android-native/app/src/main/java');
const databaseFile = path.resolve(
  'android-native/app/src/main/java/com/lumaschedule/app/data/LumaDatabase.kt',
);

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return walk(full);
    return entry.isFile() && entry.name.endsWith('.kt') ? [full] : [];
  });
}

const errors = [];

for (const file of walk(sourceRoot)) {
  const source = fs.readFileSync(file, 'utf8');
  const execSqlCalls = source.matchAll(/execSQL\s*\(\s*"([^"\n]+)"/g);

  for (const match of execSqlCalls) {
    const sql = match[1].trim();
    if (/^(SELECT|WITH|PRAGMA)\b/i.test(sql)) {
      errors.push(
        `${path.relative(process.cwd(), file)}: query/result SQL must not use execSQL(): ${sql}`,
      );
    }
  }
}

const databaseSource = fs.readFileSync(databaseFile, 'utf8');
if (!databaseSource.includes('db.enableWriteAheadLogging()')) {
  errors.push('LumaDatabase must enable WAL through SQLiteDatabase.enableWriteAheadLogging().');
}
if (!databaseSource.includes('db.setForeignKeyConstraintsEnabled(true)')) {
  errors.push('LumaDatabase must enable foreign keys through SQLiteDatabase.setForeignKeyConstraintsEnabled(true).');
}

if (errors.length > 0) {
  for (const error of errors) console.error(`::error::${error}`);
  process.exit(1);
}

console.log('Android SQLite API guard passed.');
