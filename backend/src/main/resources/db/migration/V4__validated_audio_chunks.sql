-- Rename accepted audio checkpoints without rewriting rows or their legacy JSONB properties.
alter table audio_groups rename to validated_audio_chunks;
alter table validated_audio_chunks rename column group_order to chunk_order;
-- Renaming the primary-key constraint also renames its backing index in PostgreSQL.
alter table validated_audio_chunks rename constraint audio_groups_pkey to validated_audio_chunks_pkey;
alter table validated_audio_chunks rename constraint audio_groups_generation_id_fkey to validated_audio_chunks_generation_id_fkey;
alter trigger audio_immutable on validated_audio_chunks rename to validated_audio_chunks_immutable;
