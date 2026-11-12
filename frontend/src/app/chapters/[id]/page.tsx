"use client";
import {use, useEffect, useState} from "react";
import {api, type Chapter, type Generation} from "@/lib/api";
import ChapterAudio from "@/components/ChapterAudio";
import ChapterPlacementForm from "@/components/ChapterPlacementForm";

export default function ChapterPage({params}: { params: Promise<{ id: string }> }) {
    const {id} = use(params);
    const [chapter, setChapter] = useState<Chapter>(), [job, setJob] = useState<Generation>(), [error, setError] = useState(""), [busy, setBusy] = useState(false);
    const [history, setHistory] = useState<Generation[]>([]);
    function showJob(next: Generation) {
        setJob(next);
        setHistory(previous => [next, ...previous.filter(saved => saved.id !== next.id)]);
    }
    useEffect(() => {
        let cancelled = false;
        Promise.all([api.GET("/api/v1/chapters/{chapterId}", {params: {path: {chapterId: id}}}), api.GET("/api/v1/chapters/{chapterId}/generations", {params: {path: {chapterId: id}}})]).then(([c, g]) => {
            if (cancelled) return;
            if (c.data) setChapter(c.data); else setError("Lukua ei löytynyt.");
            setHistory(g.data ?? []);
            const current = g.data?.filter(saved => saved.sourceRevisionId === c.data?.sourceRevisionId);
            setJob(current?.find(saved => ["queued", "running"].includes(saved.status)) ?? current?.[0]);
        }).catch(() => setError("Lataaminen epäonnistui."));
        return () => {
            cancelled = true;
        };
    }, [id]);
    useEffect(() => {
        if (!job || !["queued", "running"].includes(job.status)) return;
        let cancelled = false;
        const timer = setInterval(() => {
            api.GET("/api/v1/generations/{generationId}", {params: {path: {generationId: job.id}}}).then(r => {
                if (!cancelled && r.data) showJob(r.data);
            }).catch(() => {
                if (!cancelled) setError("Tilapäivitys epäonnistui. Päivitä sivu yrittääksesi uudelleen.");
            });
        }, 2000);
        return () => {
            cancelled = true;
            clearInterval(timer);
        };
    }, [job?.id, job?.status]);

    async function generate() {
        if (!chapter || busy || (job && job.status !== "succeeded")) return;
        setBusy(true);
        setError("");
        try {
            // Retain uncertain submissions, but ignore legacy keys that permanently identified old jobs.
            const key = `bookcast:pending-request:${chapter.sourceRevisionId}`;
            let requestId = localStorage.getItem(key);
            if (!requestId) {
                requestId = crypto.randomUUID();
                localStorage.setItem(key, requestId);
            }
            const r = await api.POST("/api/v1/chapters/{chapterId}/generations", {
                params: {path: {chapterId: id}},
                body: {requestId, sourceRevisionId: chapter.sourceRevisionId}
            });
            if (r.data) {
                showJob(r.data);
                localStorage.removeItem(key);
            } else setError("Generointia ei voitu aloittaa.");
        } catch {
            setError("Generointia ei voitu aloittaa.");
        } finally {
            setBusy(false);
        }
    }

    async function retry() {
        if (!job) return;
        setBusy(true);
        setError("");
        try {
            const r = await api.POST("/api/v1/generations/{generationId}/retry", {params: {path: {generationId: job.id}}});
            // Known configuration conflicts have fixed, safe backend messages; other failures stay generic.
            const message = r.error?.message;
            if (r.data) showJob(r.data);
            else setError(r.response?.status === 409
                && ["SPEECH_CONFIGURATION_CHANGED", "SPEECH_CONFIGURATION_MISMATCH", "SPEECH_CONFIGURATION_UNVERIFIED"].includes(r.error?.code ?? "")
                && typeof message === "string" && message.trim().length > 0 && message.length <= 500
                ? message : "Uudelleenyritys epäonnistui.");
        } catch {
            setError("Uudelleenyritys epäonnistui.");
        } finally {
            setBusy(false);
        }
    }

    if (!chapter) return <p role="status">{error || "Ladataan…"}</p>;
    const active = job && ["queued", "running"].includes(job.status), cost = (value: number) => `$${value.toFixed(3)}`;
    const completed = history.filter(saved => saved.status === "succeeded" && saved.id !== job?.id);
    return <><a href="/">← Kirjasto</a><h1>{chapter.title}</h1>
        <p><a href={`/books/${chapter.bookId}`}>{chapter.bookTitle}</a> · {chapter.chapterNumber == null ? "Numero puuttuu" : `Luku ${chapter.chapterNumber}`} · {chapter.blocks.filter(b => !b.excludedReason).length} lähdelohkoa</p>{error &&
            <p role="alert" className="error">{error}</p>}
        <section><h2>Podcast</h2>{!job && <><p>Luodaan suomenkielinen HOST/EXPERT-keskustelu ja kuunneltava
            äänitiedosto. Generointi käyttää maksullisia AI-palveluita.</p>
            <button disabled={busy} onClick={generate}>Generoi podcast</button>
        </>}{job?.status === "succeeded" && <><p>Uusi podcast luodaan alusta samasta lähdetekstistä nykyisillä asetuksilla.
            Generointi käyttää maksullisia AI-palveluita. Aiemmat podcastit ja äänitiedostot säilyvät.</p>
            <button disabled={busy} onClick={generate}>Generoi uusi podcast</button>
        </>}{job && <><p role="status">{{
            queued: "Jonossa",
            running: "Generointi käynnissä",
            succeeded: "Valmis",
            failed: "Generointi epäonnistui"
        }[job.status]} · {job.stage.startsWith("audio_retry:") ? `Äänen uudelleenyritys ${job.stage.split(":")[1]}/${job.totalAudioGroups} (yritys ${job.stage.split(":")[2]}/3)` : job.stage}</p>
            <p>Keskustelu: {job.completedSegments}/{job.totalSegments} ·
                Ääni: {job.completedAudioGroups}/{job.totalAudioGroups}</p>{active &&
                <progress aria-label="Generoinnin edistyminen" value={job.completedSegments + job.completedAudioGroups}
                          max={Math.max(1, job.totalSegments + job.totalAudioGroups)}/>}<p>
                <small>{job.generationModel} / {job.audioModel}<br/>Arvio:
                    keskustelu {cost(job.estimatedGenerationCost)}, ääni {cost(job.estimatedAudioCost)},
                    yhteensä {cost(job.totalEstimatedCost)}{!job.costComplete ? " (osa käyttötiedoista puuttuu)" : ""}
                </small></p>{job.status === "failed" && <><p className="error">{job.errorCode}. Valmiit osat säilyvät
                uudelleenyrityksessä.</p>
                <button disabled={busy} onClick={retry}>Yritä keskeytynyttä vaihetta uudelleen</button>
            </>}{job.attempts.some(a => a.provider === "fake") &&
                <p>Paikallinen maksuton esikatselu: ääni on testiääntä.</p>}</>}</section>
        {job && <ChapterAudio key={job.id} job={job}/>}
        {completed.length > 0 && <details>
            <summary>Aiemmat valmiit podcastit ({completed.length})</summary>
            {completed.map((saved, index) => <article key={saved.id}>
                <h3>Aiempi podcast {index + 1}</h3>
                <p>{saved.generationModel} / {saved.audioModel} · Arvio {cost(saved.totalEstimatedCost)}
                    {!saved.costComplete ? " (osa käyttötiedoista puuttuu)" : ""}</p>
                <ChapterAudio job={saved}/>
            </article>)}
        </details>}
        <ChapterPlacementForm chapter={chapter} onSaved={setChapter}/>
        <details>
            <summary>Lähdeteksti ja normalisointi</summary>
            {chapter.blocks.map(b => <article key={b.id}>{b.excludedReason &&
                <small>Ohitettu: {b.excludedReason}</small>}
                <pre>{b.text}</pre>
            </article>)}</details>
        {job?.scriptSegments.length ? <details>
            <summary>Keskustelun teksti</summary>
            {job.scriptSegments.flatMap(s => s.dialogueTurns.map((t, i) => <p key={`${s.order}-${i}`}>
                <strong>{t.speakerRole}: </strong>{t.text}</p>))}</details> : null}</>;
}
