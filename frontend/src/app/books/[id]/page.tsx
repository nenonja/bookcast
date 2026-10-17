"use client";
import {use, useEffect, useState, type FormEvent} from "react";
import {useRouter} from "next/navigation";
import {api, libraryError, type Book, type Chapter} from "@/lib/api";

export default function BookPage({params}: {params: Promise<{id: string}>}) {
  const {id} = use(params), router = useRouter();
  const [book, setBook] = useState<Book>(), [chapters, setChapters] = useState<Chapter[]>([]);
  const [error, setError] = useState(""), [busy, setBusy] = useState(false);
  useEffect(() => {
    let active = true;
    Promise.all([api.GET("/api/v1/books/{bookId}", {params:{path:{bookId:id}}}), api.GET("/api/v1/books/{bookId}/chapters", {params:{path:{bookId:id}}})]).then(([b,c]) => {
      if (!active) return;
      if (b.data && c.data) {setBook(b.data); setChapters(c.data);} else setError("Kirjaa ei löytynyt.");
    }).catch(() => {if (active) setError("Palvelimeen ei saada yhteyttä.");});
    return () => {active = false;};
  }, [id]);
  async function add(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); setBusy(true); setError("");
    const f = new FormData(e.currentTarget);
    try {
      const r = await api.POST("/api/v1/books/{bookId}/chapters", {params:{path:{bookId:id}}, body:{chapterNumber:Number(f.get("chapterNumber")), title:String(f.get("title")), text:String(f.get("text")), sourceLanguage:String(f.get("sourceLanguage"))}});
      if (r.data) router.push(`/chapters/${r.data.id}`); else setError(libraryError(r.error?.code));
    } catch {setError("Palvelimeen ei saada yhteyttä.");} finally {setBusy(false);}
  }
  if (!book) return <p role="status">{error || "Ladataan…"}</p>;
  return <><a href="/">← Kirjasto</a><h1>{book.title}</h1>{error && <p role="alert" className="error">{error}</p>}
    <section><h2>Luvut</h2>{chapters.length ? <ul>{chapters.map(c => <li key={c.id}><a href={`/chapters/${c.id}`}>{c.chapterNumber == null ? "Numero puuttuu" : `Luku ${c.chapterNumber}`}: {c.title}</a></li>)}</ul> : <p>Kirjassa ei vielä ole lukuja.</p>}</section>
    <section><h2>Lisää luku</h2><form onSubmit={add}>
      <label htmlFor="chapter-number">Luvun numero</label><input id="chapter-number" name="chapterNumber" type="number" min={1} max={2147483647} step={1} required/>
      <label htmlFor="chapter-title">Luvun nimi</label><input id="chapter-title" name="title" required maxLength={200}/>
      <label htmlFor="source-language">Lähteen kieli</label><select id="source-language" name="sourceLanguage"><option value="en">Englanti</option><option value="fi">Suomi</option></select>
      <label htmlFor="source-text">Lähdeteksti</label><textarea id="source-text" name="text" required maxLength={200000}/>
      <button disabled={busy}>{busy ? "Tuodaan…" : "Tallenna luku"}</button>
    </form></section>
  </>;
}
