import EmbeddedPostgres from 'embedded-postgres';
import { readdir, readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import pg from 'pg';

const here = path.dirname(fileURLToPath(import.meta.url));
export const migrationsDir = path.resolve(here, '..', '..', 'migrations');

const PLATFORM_BOOTSTRAP = `
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then
    create role anon nologin nosuperuser nocreatedb nocreaterole;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then
    create role authenticated nologin nosuperuser nocreatedb nocreaterole;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'service_role') then
    create role service_role nologin nosuperuser nocreatedb nocreaterole;
  end if;
end
$$;

create schema if not exists auth;

do $bootstrap$
begin
  if not exists (
    select 1 from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'auth' and p.proname = 'uid'
  ) then
    execute 'create function auth.uid() returns uuid language sql stable as $fn$ select nullif(nullif(current_setting(''request.jwt.claims'', true), '''')::jsonb ->> ''sub'', '''')::uuid $fn$';
  end if;
end
$bootstrap$;
`;

async function freePort() {
  const { createServer } = await import('node:net');
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

export async function listMigrations() {
  const files = await readdir(migrationsDir);
  return files.filter((f) => f.endsWith('.sql')).sort();
}

export async function readMigration(name) {
  return readFile(path.join(migrationsDir, name), 'utf8');
}

/**
 * Boots a throwaway PostgreSQL cluster that simulates the Supabase platform
 * boundary (roles + auth.uid()), then applies repository migrations verbatim.
 */
export async function startCluster({ applyMigrations = true } = {}) {
  const databaseDir = await mkdtemp(path.join(tmpdir(), 'pg-harness-'));
  const port = await freePort();
  const user = 'postgres';
  const password = 'postgres';

  const cluster = new EmbeddedPostgres({
    databaseDir,
    user,
    password,
    port,
    persistent: false,
  });

  await cluster.initialise();
  await cluster.start();

  try {
    const admin = new pg.Client({
      host: '127.0.0.1',
      port,
      user,
      password,
      database: 'postgres',
    });
    await admin.connect();
    await admin.query(PLATFORM_BOOTSTRAP);

    const applied = [];
    if (applyMigrations) {
      for (const name of await listMigrations()) {
        await admin.query(await readMigration(name));
        applied.push(name);
      }
    }

    const connectionOptions = () => ({
      host: '127.0.0.1',
      port,
      user,
      password,
      database: 'postgres',
    });

    async function connectActor({ role = 'authenticated', sub = null } = {}) {
      const client = new pg.Client(connectionOptions());
      await client.connect();
      if (role) await client.query(`set role ${role}`);
      if (sub) {
        await client.query(
          `select set_config('request.jwt.claims', $1, false)`,
          [JSON.stringify({ sub, role })],
        );
      } else {
        await client.query(`select set_config('request.jwt.claims', '', false)`);
      }
      return client;
    }

    async function stop() {
      await admin.end().catch(() => {});
      await cluster.stop().catch(() => {});
      await rm(databaseDir, { recursive: true, force: true });
    }

    return { admin, connectActor, applied, port, stop, databaseDir };
  } catch (error) {
    await cluster.stop().catch(() => {});
    await rm(databaseDir, { recursive: true, force: true });
    throw error;
  }
}
