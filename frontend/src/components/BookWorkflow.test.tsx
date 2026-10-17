import {afterEach, beforeEach, describe, expect, it, vi} from "vitest";
import {cleanup, fireEvent, render, screen, waitFor} from "@testing-library/react";
import type {Chapter} from "../lib/api";
import Home from "../app/page";
import BookPage from "../app/books/[id]/page";
import ChapterPlacementForm from "./ChapterPlacementForm";

const mocks = vi.hoisted(() => ({get:vi.fn(),post:vi.fn(),put:vi.fn(),push:vi.fn()}));
vi.mock("../lib/api", async original => ({...await original<typeof import("../lib/api")>(), api:{GET:mocks.get,POST:mocks.post,PUT:mocks.put}}));
vi.mock("next/navigation", () => ({useRouter:()=>({push:mocks.push})}));
vi.mock("react", async original => ({...await original<typeof import("react")>(), use:()=>({id:"book"})}));
const chapter = {id:"chapter", bookId:"book", bookTitle:"Book", title:"Chapter",sourceRevisionId:"revision",sourceLanguage:"en",sourceHash:"hash",createdAt:"2026-10-17T00:00:00Z",blocks:[]} satisfies Chapter;
beforeEach(() => vi.resetAllMocks());
afterEach(cleanup);

describe("book-oriented library", () => {
  it("lists books and legacy chapters, and creates an explicit book", async () => {
    mocks.get.mockImplementation((url:string) => Promise.resolve({data:url.endsWith("books") ? [{id:"book",title:"Book"}] : [chapter]}));
    mocks.post.mockResolvedValue({data:{id:"new-book",title:"New book"}});
    render(<Home/>);
    expect(await screen.findByRole("link",{name:"Book"})).toHaveProperty("href",expect.stringContaining("/books/book"));
    expect(screen.getByText("Järjestettävät vanhat luvut")).toBeTruthy();
    expect(screen.getByRole("link",{name:"Chapter"}).getAttribute("href")).toBe("/chapters/chapter");
    fireEvent.change(screen.getByLabelText("Kirjan nimi"),{target:{value:"New book"}});
    fireEvent.click(screen.getByRole("button",{name:"Luo kirja"}));
    await waitFor(() => expect(mocks.post).toHaveBeenCalledWith("/api/v1/books",{body:{title:"New book"}}));
    await waitFor(() => expect(mocks.push).toHaveBeenCalledWith("/books/new-book"));
  });

  it("shows numerical chapter ordering and imports into the selected book", async () => {
    mocks.get.mockImplementation((url:string) => Promise.resolve({data:url.endsWith("/chapters") ? [{...chapter,id:"two",chapterNumber:2},{...chapter,id:"ten",chapterNumber:10}] : {id:"book",title:"Book"}}));
    mocks.post.mockResolvedValue({data:{...chapter,id:"new-chapter",chapterNumber:3}});
    render(<BookPage params={Promise.resolve({id:"book"})}/>);
    await screen.findByRole("heading",{name:"Book"});
    const chapterLinks=screen.getAllByRole("link").filter(link=>link.getAttribute("href")?.startsWith("/chapters"));
    expect(chapterLinks.map(link=>link.textContent)).toEqual(["Luku 2: Chapter","Luku 10: Chapter"]);
    fireEvent.change(screen.getByLabelText("Luvun numero"),{target:{value:"3"}});
    fireEvent.change(screen.getByLabelText("Luvun nimi"),{target:{value:"New chapter"}});
    fireEvent.change(screen.getByLabelText("Lähdeteksti"),{target:{value:"Source text"}});
    fireEvent.click(screen.getByRole("button",{name:"Tallenna luku"}));
    await waitFor(()=>expect(mocks.post).toHaveBeenCalledWith("/api/v1/books/{bookId}/chapters",{params:{path:{bookId:"book"}},body:{chapterNumber:3,title:"New chapter",text:"Source text",sourceLanguage:"en"}}));
    await waitFor(()=>expect(mocks.push).toHaveBeenCalledWith("/chapters/new-chapter"));
  });

  it("organizes a legacy chapter without generating or importing a revision", async () => {
    mocks.get.mockResolvedValue({data:[{id:"book",title:"Book"},{id:"correct",title:"Correct book"}]});
    const moved={...chapter,bookId:"correct",chapterNumber:2};
    mocks.put.mockResolvedValue({data:moved});
    const onSaved=vi.fn();render(<ChapterPlacementForm chapter={chapter} onSaved={onSaved}/>);
    await screen.findByRole("option",{name:"Correct book"});
    fireEvent.change(screen.getByLabelText("Kirja"),{target:{value:"correct"}});
    fireEvent.change(screen.getByLabelText("Luvun numero"),{target:{value:"2"}});
    fireEvent.click(screen.getByRole("button",{name:"Tallenna kirja ja numero"}));
    await waitFor(()=>expect(onSaved).toHaveBeenCalledWith(moved));
    expect(mocks.put).toHaveBeenCalledWith("/api/v1/chapters/{chapterId}/placement",{params:{path:{chapterId:"chapter"}},body:{bookId:"correct",chapterNumber:2}});
    expect(mocks.post).not.toHaveBeenCalled();
  });

  it("shows duplicate-number error without replacing the current chapter", async () => {
    mocks.get.mockResolvedValue({data:[{id:"book",title:"Book"}]});
    mocks.put.mockResolvedValue({error:{code:"CHAPTER_NUMBER_CONFLICT"}});
    const saved=vi.fn();render(<ChapterPlacementForm chapter={chapter} onSaved={saved}/>);
    await screen.findByRole("option",{name:"Book"});
    fireEvent.change(screen.getByLabelText("Luvun numero"),{target:{value:"2"}});
    fireEvent.click(screen.getByRole("button",{name:"Tallenna kirja ja numero"}));
    expect(await screen.findByRole("alert")).toHaveProperty("textContent",expect.stringContaining("jo tämän numeroinen"));
    expect(saved).not.toHaveBeenCalled();
  });
});
