# PR_REVIEW_AI_AGENT
This is an AI agent that you can run locally and through the GROQ api to review your PR.

TO RUN THE PROJECT
  - Install any LLM model in your local environment (prefer to use the Ollama Mistral model)
  - First run Java code, then start Python file (rag_agent.py) and make sure that the text file is in the same folder as the .py file
  - Then generate a token of GITHUB for getting data of the PRs
  - Create a webhook to receive the event of pull_request_open.
  - Then create a PR to trigger your Java webhook to get the data and review the difference.
