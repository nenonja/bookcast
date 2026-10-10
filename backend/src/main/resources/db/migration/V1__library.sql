create table books (id uuid primary key, title text not null);
create table chapters (id uuid primary key, book_id uuid not null references books, title text not null, created_at timestamptz not null default now());
create table source_revisions (id uuid primary key, chapter_id uuid not null references chapters, source_language text not null, content_hash text not null, created_at timestamptz not null default now());
create table source_blocks (id uuid primary key, revision_id uuid not null references source_revisions, block_order int not null, content text not null, excluded_reason text, unique(revision_id,block_order));
create function immutable_source() returns trigger language plpgsql as $$ begin raise exception 'Source revisions and blocks are immutable'; end $$;
create trigger revisions_immutable before update or delete on source_revisions for each row execute function immutable_source();
create trigger blocks_immutable before update or delete on source_blocks for each row execute function immutable_source();
