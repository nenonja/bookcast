import createClient from "openapi-fetch";
import type { paths, components } from "./api-schema";
export const api = createClient<paths>({baseUrl:""});
export type Chapter = components["schemas"]["Chapter"];
export type Generation = components["schemas"]["Generation"];
export type Book = components["schemas"]["Book"];
export function libraryError(code?: string): string {
  if (code === "CHAPTER_NUMBER_CONFLICT") return "Kirjassa on jo tämän numeroinen luku. Valitse toinen numero.";
  if (code === "INVALID_CHAPTER_NUMBER") return "Luvun numeron on oltava positiivinen kokonaisluku.";
  if (code === "NOT_FOUND") return "Kirjaa tai lukua ei löytynyt. Päivitä sivu.";
  return "Tallennus epäonnistui. Tarkista tiedot ja yritä uudelleen.";
}
