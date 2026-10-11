import test, { after, before, describe } from 'node:test';
import assert from 'node:assert/strict';
import { startCluster } from './helpers/platform.mjs';

let cluster;

before(async () => {
  cluster = await startCluster({ applyMigrations: false });
});

after(async () => {
  await cluster?.stop();
});

describe('local PostgreSQL harness', () => {
  test('boots and applies platform bootstrap', async () => {
    const roles = await cluster.admin.query(
      `select rolname from pg_roles where rolname in ('anon','authenticated','service_role') order by rolname`,
    );
    assert.deepEqual(
      roles.rows.map((r) => r.rolname),
      ['anon', 'authenticated', 'service_role'],
    );
    const uid = await cluster.admin.query(`select auth.uid() as uid`);
    assert.equal(uid.rows[0].uid, null);
  });

  test('row level security actually applies to non-owner roles', async () => {
    await cluster.admin.query(`
      create table rls_probe (
        owner uuid not null,
        label text not null
      );
      insert into rls_probe values
        ('00000000-0000-0000-0000-00000000000a', 'a'),
        ('00000000-0000-0000-0000-00000000000b', 'b');
      alter table rls_probe enable row level security;
      alter table rls_probe force row level security;
      create policy rls_probe_own on rls_probe
        for select using (owner = auth.uid());
      grant select on rls_probe to authenticated;
    `);

    const asA = await cluster.connectActor({
      role: 'authenticated',
      sub: '00000000-0000-0000-0000-00000000000a',
    });
    const seen = await asA.query('select label from rls_probe');
    assert.deepEqual(
      seen.rows.map((r) => r.label),
      ['a'],
      'RLS must filter rows to the JWT owner',
    );
    await asA.end();

    const asAnon = await cluster.connectActor({ role: 'anon', sub: null });
    await assert.rejects(
      () => asAnon.query('select label from rls_probe'),
      /permission denied/,
      'anon must hold no grants',
    );
    await asAnon.end();

    const noClaims = await cluster.connectActor({ role: 'authenticated', sub: null });
    const empty = await noClaims.query('select label from rls_probe');
    assert.deepEqual(empty.rows, [], 'authenticated without JWT sees nothing');
    await noClaims.end();

    await cluster.admin.query('drop table rls_probe');
  });

  test('SECURITY DEFINER RPC sees owner through auth.uid() while anon is revoked', async () => {
    await cluster.admin.query(`
      create table rpc_probe (owner uuid not null, label text not null);
      insert into rpc_probe values ('00000000-0000-0000-0000-00000000000a', 'a');
      alter table rpc_probe enable row level security;
      alter table rpc_probe force row level security;
      create policy rpc_probe_own on rpc_probe for select using (owner = auth.uid());
      create function public.whoami() returns uuid
        language sql security definer set search_path = public, auth
        as $$ select auth.uid() $$;
      revoke all on function public.whoami() from public, anon;
      grant execute on function public.whoami() to authenticated;
    `);

    const asA = await cluster.connectActor({
      role: 'authenticated',
      sub: '00000000-0000-0000-0000-00000000000a',
    });
    const who = await asA.query('select whoami() as uid');
    assert.equal(who.rows[0].uid, '00000000-0000-0000-0000-00000000000a');
    await asA.end();

    const asAnon = await cluster.connectActor({ role: 'anon', sub: null });
    await assert.rejects(() => asAnon.query('select whoami()'), /permission denied/);
    await asAnon.end();

    await cluster.admin.query('drop table rpc_probe; drop function public.whoami();');
  });
});
