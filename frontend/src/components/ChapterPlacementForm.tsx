"use client";
import {useEffect, useState, type FormEvent} from "react";
import {api, libraryError, type Book, type Chapter} from "../lib/api";

export default function ChapterPlacementForm({chapter, onSaved}: {chapter: Chapter; onSaved: (chapter: Chapter) => void}) {
  const [books, setBooks] = useState<Book[]>([]), [bookId, setBookId] = useState(chapter.bookId);
  const [number, setNumber] = useState(chapter.chapterNumber?.toString() ?? "");
  const [error, setError] = useState(""), [saved, setSaved] = useState(false), [busy, setBusy] = useState(false);
  useEffect(() => {
    let active = true;
    api.GET("/api/v1/books").then(r => {if (active) {if (r.data) setBooks(r.data); else setError("Kirjojen lataaminen epäonnistui.");}}).catch(() => {if (active) setError("Kirjojen lataaminen epäonnistui.");});
    return () => {active = false;};
  }, []);
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); setBusy(true); setError(""); setSaved(false);
    try {
      const r = await api.PUT("/api/v1/chapters/{chapterId}/placement", {params:{path:{chapterId:chapter.id}}, body:{bookId, chapterNumber:Number(number)}});
      if (r.data) {onSaved(r.data); setSaved(true);} else setError(libraryError(r.error?.code));
    } catch {setError("Palvelimeen ei saada yhteyttä.");} finally {setBusy(false);}
  }
  return <section><h2>Kirja ja luvun numero</h2><p>Järjestä luku muuttamatta sen lähdettä tai valmista podcastia. <a href="/">Luo tarvittaessa ensin uusi kirja kirjastossa.</a></p>
    {chapter.chapterNumber == null && <p>Tämä vanha tuonti odottaa oikeaa kirjaa ja numeroa.</p>}
    {error && <p role="alert" className="error">{error}</p>}{saved && <p role="status">Luvun sijainti tallennettu.</p>}
    <form onSubmit={submit}>
      <label htmlFor="placement-book">Kirja</label><select id="placement-book" required value={bookId} onChange={e => setBookId(e.target.value)}>{books.map(b => <option key={b.id} value={b.id}>{b.title}</option>)}</select>
      <label htmlFor="placement-number">Luvun numero</label><input id="placement-number" type="number" required min={1} max={2147483647} step={1} value={number} onChange={e => setNumber(e.target.value)}/>
      <button disabled={busy || !books.length}>{busy ? "Tallennetaan…" : "Tallenna kirja ja numero"}</button>
    </form>
  </section>;
}
