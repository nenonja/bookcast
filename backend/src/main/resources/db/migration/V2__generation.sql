create table generations (
 id uuid primary key, request_id uuid not null unique, chapter_id uuid not null, revision_id uuid not null,
 status text not null check(status in ('queued','running','succeeded','failed')), stage text not null,
 total_segments int not null default 0, total_audio int not null default 0,
 generation_model text not null, audio_model text not null,
 error_code text, retryable boolean not null default false, audio_path text, created_at timestamptz not null default now());
create unique index one_active_generation on generations(revision_id) where status in ('queued','running');
create table script_segments (generation_id uuid not null references generations, segment_order int not null, payload jsonb not null, primary key(generation_id,segment_order));
create table audio_groups (generation_id uuid not null references generations, group_order int not null, payload jsonb not null, primary key(generation_id,group_order));
create table generation_attempts (id uuid primary key, generation_id uuid not null references generations, payload jsonb not null);
create function immutable_generation_checkpoint() returns trigger language plpgsql as $$ begin raise exception 'Accepted generation checkpoints are immutable'; end $$;
create trigger script_immutable before update or delete on script_segments for each row execute function immutable_generation_checkpoint();
create trigger audio_immutable before update or delete on audio_groups for each row execute function immutable_generation_checkpoint();
