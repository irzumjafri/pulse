#Import Statements
from flask import Flask, request, jsonify
from langchain_ollama import OllamaLLM
from langchain_core.prompts import ChatPromptTemplate

# Initialize Flask app
app = Flask(__name__)

# Initialize model and prompt template
template = """
Answer the Question Below:

Here is the conversation history: {context}

Question: {question}

Answer: 
"""

# Initialize the model from OllamaLLM
#model = OllamaLLM(model="llama3.2")
#Finglish Model
model = OllamaLLM(model="openintegrator/poro-34b-chat")

prompt = ChatPromptTemplate.from_template(template)
chain = prompt | model

# Context storage: global context shared by all users
global_context = """"
This is Pulse AI. The AI voice assistant helps with managing workforce, providing important updates on patients.
"""

# User-specific context storage (using a dictionary to store by user_id)
user_contexts = {}

@app.route('/chat', methods=['POST'])
def chat():
    data = request.json
    user_id = data.get("user_id")
    user_input = data.get("message")

    # If user context doesn't exist, create a new one
    if user_id not in user_contexts:
        user_contexts[user_id] = ""

    # Include global context in each user's specific context
    context = global_context + "\n" + user_contexts[user_id]

    # Generate AI's response
    result = chain.invoke({"context": context, "question": user_input})

    # Update user context with the latest exchange
    user_contexts[user_id] += f"\nUser: {user_input}\nAI: {result}"

    return jsonify({"response": result})

@app.route('/set_global_context', methods=['POST'])
def set_global_context():
    global global_context
    data = request.json
    global_context = data.get("context")
    return jsonify({"message": "Global context updated successfully"}), 200

@app.route('/reset_user_context', methods=['POST'])
def reset_user_context():
    data = request.json
    user_id = data.get("user_id")
    if user_id in user_contexts:
        user_contexts[user_id] = ""
        return jsonify({"message": f"User context for {user_id} has been reset."}), 200
    else:
        return jsonify({"error": "User ID not found."}), 404

if __name__ == '__main__':
    # Run the Flask app on port 5000
    app.run(debug=True, host='0.0.0.0', port=5000)
