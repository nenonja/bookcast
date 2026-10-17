-- Existing Book/Chapter associations are retained exactly as imported.
-- NULL explicitly means legacy chapter awaiting owner-assigned numbering; never infer from titles.
alter table chapters add column chapter_number integer;
alter table chapters add constraint chapter_number_positive check (chapter_number > 0);
alter table chapters add constraint chapter_number_per_book unique (book_id, chapter_number);
