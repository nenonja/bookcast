"use client";
import {useEffect, useState, type FormEvent} from "react";
import {api, libraryError, type Book, type Chapter} from "@/lib/api";
import {useRouter} from "next/navigation";

export default function Home() {
  const [books, setBooks] = useState<Book[]>([]), [legacy, setLegacy] = useState<Chapter[]>([]);
  const [error, setError] = useState(""), [busy, setBusy] = useState(false);
  const router = useRouter();
  useEffect(() => {
    let active = true;
    Promise.all([api.GET("/api/v1/books"), api.GET("/api/v1/chapters")]).then(([b,c]) => {
      if (!active) return;
      if (b.data && c.data) { setBooks(b.data); setLegacy(c.data.filter(ch => ch.chapterNumber == null)); }
      else setError("Kirjaston lataaminen epäonnistui.");
    }).catch(() => {if (active) setError("Palvelimeen ei saada yhteyttä.");});
    return () => {active = false;};
  }, []);
  async function create(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); setBusy(true); setError("");
    const form = new FormData(e.currentTarget);
    try {
      const r = await api.POST("/api/v1/books", {body: {title: String(form.get("title"))}});
      if (r.data) router.push(`/books/${r.data.id}`); else setError(libraryError(r.error?.code));
    } catch {setError("Palvelimeen ei saada yhteyttä.");} finally {setBusy(false);}
  }
  return <><h1>Kirjasto</h1><p>Avaa kirja tai luo uusi. Lisää ja kuuntele sen luvut omaan tahtiisi.</p>
    {error && <p role="alert" className="error">{error}</p>}
    <section><h2>Kirjat</h2>{books.length ? <ul>{books.map(b => <li key={b.id}><a href={`/books/${b.id}`}>{b.title}</a></li>)}</ul> : <p>Ei vielä kirjoja.</p>}</section>
    <section><h2>Luo kirja</h2><form onSubmit={create}><label htmlFor="book-title">Kirjan nimi</label><input id="book-title" name="title" required maxLength={200}/><button disabled={busy}>{busy ? "Tallennetaan…" : "Luo kirja"}</button></form></section>
    {legacy.length > 0 && <section><h2>Järjestettävät vanhat luvut</h2><p>Vanhojen tuontien kirjat ja lähteet on säilytetty. Valitse luvulle oikea kirja ja numero sen sivulta. Ääntä ei generoida uudelleen.</p>
      <ul>{legacy.map(c => <li key={c.id}><a href={`/chapters/${c.id}`}>{c.title}</a> <small>Nykyinen kirja: {c.bookTitle} · numero puuttuu</small></li>)}</ul></section>}
  </>;
}
