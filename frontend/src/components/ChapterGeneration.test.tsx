import {afterEach, beforeEach, describe, expect, it, vi} from "vitest";
import {cleanup, fireEvent, render, screen, waitFor} from "@testing-library/react";
import type {Chapter, Generation} from "../lib/api";
import ChapterPage from "../app/chapters/[id]/page";

const mocks = vi.hoisted(() => ({get: vi.fn(), post: vi.fn()}));
vi.mock("../lib/api", async original => ({...await original<typeof import("../lib/api")>(), api: {GET: mocks.get, POST: mocks.post}}));
vi.mock("react", async original => ({...await original<typeof import("react")>(), use: () => ({id: "chapter"})}));
vi.mock("./ChapterPlacementForm", () => ({default: () => null}));

const chapter = {id: "chapter", bookId: "book", bookTitle: "Book", title: "Chapter",
  sourceRevisionId: "revision", sourceLanguage: "en", sourceHash: "hash",
  createdAt: "2026-11-10T00:00:00Z", blocks: []} satisfies Chapter;
const old = {id: "original", chapterId: "chapter", sourceRevisionId: "revision", status: "succeeded",
  stage: "complete", completedSegments: 15, totalSegments: 15, completedAudioGroups: 155, totalAudioGroups: 155,
  generationModel: "previous-text-model", audioModel: "previous-audio-model", estimatedGenerationCost: .1,
  estimatedAudioCost: 1, totalEstimatedCost: 1.1, costComplete: true,
  audioUrl: "/api/v1/generations/original/audio", audioDownloadUrl: "/api/v1/generations/original/audio/download",
  retryable: false, scriptSegments: [], attempts: []} satisfies Generation;
const fresh: Generation = {...old, id: "fresh", status: "queued", stage: "queued", completedSegments: 0,
  totalSegments: 0, completedAudioGroups: 0, totalAudioGroups: 0, generationModel: "current-text-model",
  audioModel: "current-audio-model", estimatedGenerationCost: 0, estimatedAudioCost: 0, totalEstimatedCost: 0,
  audioUrl: undefined, audioDownloadUrl: undefined};
const pendingKey = "bookcast:pending-request:revision";

beforeEach(() => {
  vi.resetAllMocks();
  localStorage.clear();
  vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000001");
});
afterEach(() => {cleanup(); vi.restoreAllMocks(); localStorage.clear();});

function load(jobs: Generation[] = [old]) {
  mocks.get.mockImplementation((url: string) => Promise.resolve({data: url.endsWith("/generations") ? jobs : chapter}));
  return render(<ChapterPage params={Promise.resolve({id: "chapter"})}/>);
}

describe("fresh chapter generation", () => {
  it("explicitly creates a new job with a fresh key and keeps the completed audio accessible", async () => {
    localStorage.setItem("bookcast:request:revision", "original-request");
    mocks.post.mockResolvedValue({data: fresh});
    load();
    const button = await screen.findByRole("button", {name: "Generoi uusi podcast"});
    expect(screen.getByText(/Generointi käyttää maksullisia AI-palveluita/)).toBeTruthy();
    expect(mocks.post).not.toHaveBeenCalled();
    fireEvent.click(button);
    await waitFor(() => expect(screen.getByRole("status").textContent).toContain("Jonossa"));
    expect(mocks.post).toHaveBeenCalledExactlyOnceWith("/api/v1/chapters/{chapterId}/generations", {
      params: {path: {chapterId: chapter.id}},
      body: {requestId: "00000000-0000-4000-8000-000000000001", sourceRevisionId: chapter.sourceRevisionId}
    });
    expect(localStorage.getItem(pendingKey)).toBeNull();
    expect(localStorage.getItem("bookcast:request:revision")).toBe("original-request");
    fireEvent.click(screen.getByText("Aiemmat valmiit podcastit (1)"));
    expect(screen.getByRole("link", {name: "Download audio"}).getAttribute("href")).toBe(old.audioDownloadUrl);
    expect(screen.getByLabelText("Luvun podcast").getAttribute("src")).toBe(old.audioUrl);
    expect(screen.queryByRole("button", {name: /Generoi/})).toBeNull();
    expect(screen.queryByRole("button", {name: /Yritä keskeytynyttä/})).toBeNull();
  });

  it("reuses the pending key after a lost response, including after reloading", async () => {
    mocks.post.mockRejectedValueOnce(new Error("response lost")).mockResolvedValueOnce({data: fresh});
    load();
    fireEvent.click(await screen.findByRole("button", {name: "Generoi uusi podcast"}));
    await screen.findByRole("alert");
    const key = localStorage.getItem(pendingKey);
    expect(key).toBe("00000000-0000-4000-8000-000000000001");
    cleanup();
    load();
    fireEvent.click(await screen.findByRole("button", {name: "Generoi uusi podcast"}));
    await waitFor(() => expect(screen.getByRole("status").textContent).toContain("Jonossa"));
    expect(mocks.post.mock.calls[0]).toEqual(mocks.post.mock.calls[1]);
    expect(crypto.randomUUID).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem(pendingKey)).toBeNull();
  });

  it("preserves the pending key and old playback on an unsuccessful API response", async () => {
    mocks.post.mockResolvedValue({error: {code: "INTERNAL_ERROR"}});
    load();
    fireEvent.click(await screen.findByRole("button", {name: "Generoi uusi podcast"}));
    await screen.findByRole("alert");
    expect(localStorage.getItem(pendingKey)).not.toBeNull();
    expect(screen.getByRole("link", {name: "Download audio"}).getAttribute("href")).toBe(old.audioDownloadUrl);
    expect(screen.getByRole("status").textContent).toContain("Valmis");
  });

  it("lists older completed audio after refresh and uses another fresh key for the next explicit generation", async () => {
    const completed: Generation = {...old, id: "second", audioUrl: "/second/audio", audioDownloadUrl: "/second/download"};
    vi.mocked(crypto.randomUUID)
      .mockReturnValueOnce("00000000-0000-4000-8000-000000000001")
      .mockReturnValueOnce("00000000-0000-4000-8000-000000000002");
    mocks.post.mockResolvedValueOnce({data: completed}).mockResolvedValueOnce({data: fresh});
    load();
    fireEvent.click(await screen.findByRole("button", {name: "Generoi uusi podcast"}));
    await waitFor(() => expect(screen.getAllByLabelText("Luvun podcast").some(audio => audio.getAttribute("src") === completed.audioUrl)).toBe(true));
    expect(localStorage.getItem(pendingKey)).toBeNull();
    cleanup();
    load([completed, old]);
    await screen.findByRole("button", {name: "Generoi uusi podcast"});
    fireEvent.click(screen.getByText("Aiemmat valmiit podcastit (1)"));
    expect(screen.getAllByRole("link", {name: "Download audio"}).map(link => link.getAttribute("href")))
      .toEqual([completed.audioDownloadUrl, old.audioDownloadUrl]);
    const speeds = screen.getAllByLabelText("Nopeus");
    expect(speeds).toHaveLength(2);
    expect(new Set(speeds.map(control => control.id)).size).toBe(2);
    fireEvent.change(speeds[1], {target: {value: "1.5"}});
    const players = screen.getAllByLabelText<HTMLAudioElement>("Luvun podcast");
    expect(players[0].playbackRate).toBe(1);
    expect(players[1].playbackRate).toBe(1.5);
    expect(mocks.post).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", {name: "Generoi uusi podcast"}));
    await waitFor(() => expect(screen.getByRole("status").textContent).toContain("Jonossa"));
    expect(screen.getByText("Aiemmat valmiit podcastit (2)")).toBeTruthy();
    expect(screen.getAllByRole("link", {name: "Download audio"}).map(link => link.getAttribute("href")))
      .toEqual([completed.audioDownloadUrl, old.audioDownloadUrl]);
    expect(mocks.post.mock.calls.map(call => call[1].body.requestId)).toEqual([
      "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002"
    ]);
  });

  it.each(["queued", "running"] as const)("selects existing %s work even if a completed job is listed first", async status => {
    load([old, {...fresh, status}]);
    await screen.findByText("Aiemmat valmiit podcastit (1)");
    expect(screen.queryByRole("button", {name: /Generoi/})).toBeNull();
    expect(mocks.post).not.toHaveBeenCalled();
    expect(screen.getByText("Aiemmat valmiit podcastit (1)")).toBeTruthy();
  });

  it("keeps failed-job retry separate from creating a fresh job", async () => {
    load([{...fresh, status: "failed", stage: "failed"}, old]);
    await screen.findByRole("button", {name: "Yritä keskeytynyttä vaihetta uudelleen"});
    expect(screen.queryByRole("button", {name: /Generoi/})).toBeNull();
    expect(mocks.post).not.toHaveBeenCalled();
  });
});
