from flask import Flask, request, jsonify
from langchain.vectorstores import FAISS
from langchain.embeddings import OllamaEmbeddings
from langchain.llms import Ollama
from langchain.chains import RetrievalQA
from langchain.docstore.document import Document

app = Flask(__name__)

# Load model and embedding
llm = Ollama(model="mistral")
embedding = OllamaEmbeddings(model="mistral")
db = None

def load_knowledgebase():
    global db
    documents = []
    with open("your_review_data.txt", "r", encoding="utf-8") as file:
        lines = file.readlines()
        for line in lines:
            if line.strip():
                documents.append(Document(page_content=line.strip()))
    db = FAISS.from_documents(documents, embedding)

@app.route('/review', methods=['POST'])
def review():
    data = request.get_json()
    diff = data.get("diff")

    if not diff:
        return jsonify({"error": "No diff provided"}), 400

    retriever = db.as_retriever(search_kwargs={"k": 3})
    chain = RetrievalQA.from_chain_type(llm=llm, retriever=retriever)

    result = chain.run(f"Please review the following pull request diff:\n{diff}")
    return jsonify({"review": result})

if __name__ == "__main__":
    load_knowledgebase()
    app.run(port=5000)
