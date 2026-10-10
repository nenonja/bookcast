import createClient from "openapi-fetch";
import type { paths, components } from "./api-schema";
export const api = createClient<paths>({baseUrl:""});
export type Chapter = components["schemas"]["Chapter"];
export type Generation = components["schemas"]["Generation"];
