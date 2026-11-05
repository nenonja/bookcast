import {afterEach, beforeEach, describe, expect, it, vi} from "vitest";
import {cleanup, fireEvent, render, screen, waitFor} from "@testing-library/react";
import type {Chapter, Generation} from "../lib/api";
import ChapterPage from "../app/chapters/[id]/page";

const mocks = vi.hoisted(() => ({get: vi.fn(), post: vi.fn()}));
vi.mock("../lib/api", async original => ({...await original<typeof import("../lib/api")>(), api: {GET: mocks.get, POST: mocks.post}}));
vi.mock("react", async original => ({...await original<typeof import("react")>(), use: () => ({id: "chapter"})}));
vi.mock("./ChapterPlacementForm", () => ({default: () => null}));
vi.mock("./ChapterAudio", () => ({default: () => null}));

const chapter = {id: "chapter", bookId: "book", bookTitle: "Book", title: "Chapter",
  sourceRevisionId: "revision", sourceLanguage: "en", sourceHash: "hash",
  createdAt: "2026-10-17T00:00:00Z", blocks: []} satisfies Chapter;
const job = {id: "job", chapterId: "chapter", sourceRevisionId: "revision", status: "failed",
  stage: "audio", completedSegments: 1, totalSegments: 1, completedAudioGroups: 1, totalAudioGroups: 2,
  generationModel: "gpt-5.4", audioModel: "gpt-realtime-2.1-mini", estimatedGenerationCost: .01,
  estimatedAudioCost: .02, totalEstimatedCost: .03, costComplete: false,
  errorCode: "PROCESS_INTERRUPTED", retryable: true, scriptSegments: [], attempts: []} satisfies Generation;
const mismatch = "Speech settings differ from this generation. Restore its original model, voices and speed before resuming.";
const unverified = "Cannot verify this generation's speech settings. Check its saved configuration before resuming.";
const fallback = "Uudelleenyritys epäonnistui.";
const privateDetails = "private API key and provider body in /private/attempt/render-config.json";

beforeEach(() => {
  vi.resetAllMocks();
  mocks.get.mockImplementation((url: string) => Promise.resolve({data: url.endsWith("/generations") ? [job] : chapter}));
});
afterEach(cleanup);

async function retry() {
  render(<ChapterPage params={Promise.resolve({id: "chapter"})}/>);
  fireEvent.click(await screen.findByRole("button", {name: "Yritä keskeytynyttä vaihetta uudelleen"}));
}

describe("chapter manual retry errors", () => {
  it.each([
    ["SPEECH_CONFIGURATION_CHANGED", mismatch],
    ["SPEECH_CONFIGURATION_MISMATCH", mismatch],
    ["SPEECH_CONFIGURATION_UNVERIFIED", unverified],
  ])("shows the safe 409 explanation for %s without replacing the job", async (code, message) => {
    mocks.post.mockResolvedValue({response: {status: 409}, error: {code, message, diagnostics: privateDetails}});
    await retry();
    expect((await screen.findByRole("alert")).textContent).toBe(message);
    expect(screen.getByRole("status").textContent).toContain("Generointi epäonnistui");
    expect(screen.getByText(/Ääni: 1\/2/)).toBeTruthy();
    expect(screen.queryByText(privateDetails)).toBeNull();
    expect(mocks.post).toHaveBeenCalledExactlyOnceWith("/api/v1/generations/{generationId}/retry", {params: {path: {generationId: job.id}}});
    await waitFor(() => expect(screen.getByRole("button").hasAttribute("disabled")).toBe(false));
  });

  it.each([
    ["unavailable response", {}],
    ["missing HTTP status", {error: {code: "SPEECH_CONFIGURATION_MISMATCH", message: mismatch}}],
    ["unexpected HTTP status", {response: {status: 500}, error: {code: "SPEECH_CONFIGURATION_MISMATCH", message: privateDetails}}],
    ["unexpected code", {response: {status: 409}, error: {code: "PROVIDER_ERROR", message: privateDetails}}],
    ["missing code", {response: {status: 409}, error: {message: privateDetails}}],
    ["missing message", {response: {status: 409}, error: {code: "SPEECH_CONFIGURATION_MISMATCH"}}],
    ["blank message", {response: {status: 409}, error: {code: "SPEECH_CONFIGURATION_MISMATCH", message: "   "}}],
    ["object message", {response: {status: 409}, error: {code: "SPEECH_CONFIGURATION_MISMATCH", message: {diagnostics: privateDetails}}}],
    ["oversized message", {response: {status: 409}, error: {code: "SPEECH_CONFIGURATION_MISMATCH", message: "x".repeat(501)}}],
    ["raw error body", {response: {status: 409}, error: privateDetails}],
    ["null error body", {response: {status: 409}, error: null}],
    ["null result", null],
  ])("uses the generic fallback for %s", async (_name, result) => {
    mocks.post.mockResolvedValue(result);
    await retry();
    expect((await screen.findByRole("alert")).textContent).toBe(fallback);
    expect(screen.queryByText(privateDetails)).toBeNull();
    expect(mocks.post).toHaveBeenCalledTimes(1);
  });

  it.each([new Error(privateDetails), new SyntaxError(privateDetails)])(
    "keeps transport and JSON parsing failures private (%s)", async error => {
      mocks.post.mockRejectedValue(error);
      await retry();
      expect((await screen.findByRole("alert")).textContent).toBe(fallback);
      expect(screen.queryByText(privateDetails)).toBeNull();
    });

  it("clears a previous explanation when a later retry succeeds", async () => {
    mocks.post.mockResolvedValueOnce({response: {status: 409}, error: {code: "SPEECH_CONFIGURATION_MISMATCH", message: mismatch}})
      .mockResolvedValueOnce({response: {status: 202}, data: {...job, status: "queued", stage: "queued"}});
    await retry();
    await screen.findByRole("alert");
    const button = screen.getByRole("button");
    await waitFor(() => expect(button.hasAttribute("disabled")).toBe(false));
    fireEvent.click(button);
    await waitFor(() => expect(screen.getByRole("status").textContent).toContain("Jonossa"));
    expect(screen.queryByRole("alert")).toBeNull();
    expect(mocks.post).toHaveBeenCalledTimes(2);
  });
});
