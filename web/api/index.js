// Vercel Node.js serverless function - single entry point for every dynamic
// route. Static files in /public are served by Vercel's filesystem handler.
import { handle } from '../src/app.mjs';

export default async function handler(req, res) {
  return handle(req, res);
}

export const config = {
  maxDuration: 30,
  memory: 512,
};
