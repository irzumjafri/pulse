from flask import Flask, request, jsonify
from flask_cors import CORS
from langchain_ollama import OllamaLLM
from langchain_core.prompts import ChatPromptTemplate
import pandas as pd

# Initialize Flask app
app = Flask(__name__)
CORS(app)  # Enable CORS for all origins

# Initialize model and prompt template
template = """
Answer the Question Below. If you are providing patient details, only provide information that can be found in the context and if you are providing something outside the context, mention that clearly.

Here is the conversation history: {context}

Question: {question}

Answer: 
"""

# Initialize the model from OllamaLLM
model = OllamaLLM(model="llama3.2")

#Finglish Model
# model = OllamaLLM(model="openintegrator/poro-34b-chat")

prompt = ChatPromptTemplate.from_template(template)
chain = prompt | model

# Context storage: global context shared by all users
global_context = """
You are a fellow nurse at the hospital at the hospital reception, helping share patient data. Your name is Pulse AI. and you are a voice assistant that helps with managing workforce, providing important updates on patients. 
"""

# User-specific context storage (using a dictionary to store by user_id)
user_contexts = {}

# Load patient data from CSV
patient_data_path = "patient_data.csv"  # Path to the patient data file
patient_data = pd.read_csv(patient_data_path)

# Helper function to get patient details by room number
def get_patient_details_by_room(room_number):
    patient_info = patient_data[patient_data["Room Number"] == room_number]
    if not patient_info.empty:
        patient = patient_info.iloc[0]
        return (
            f"Patient Name: {patient['Patient Name']}, Room Number: {patient['Room Number']}, "
            f"Condition: {patient['Condition']}, Diagnosis: {patient['Diagnosis']}, "
            f"Undergoing Treatments: {patient['Undergoing Treatments']}, Assigned Nurse: {patient['Assigned Nurse (Number only)']}"
        )
    return None

@app.route('/test', methods=['GET'])
def test():
    return jsonify({"message": "Server is running!"}), 200

@app.route('/chat', methods=['POST'])
def chat():
    data = request.json
    user_id = data.get("user_id")
    user_input = data.get("message")
    print(user_id)
    print(user_input)
    # If user context doesn't exist, create a new one
    if user_id not in user_contexts:
        user_contexts[user_id] = ""

    # Include global context in each user's specific context
    context = global_context + "\n" + user_contexts[user_id]

    # Check for patient-specific queries by room number
    patient_details = None
    for room_number in patient_data["Room Number"].unique():
        if str(room_number) in user_input:
            patient_details = get_patient_details_by_room(room_number)
            break

    if patient_details:
        context += f"\nPatient Details: {patient_details}"

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
