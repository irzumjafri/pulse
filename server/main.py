from langdetect import detect
from flask import Flask, request, jsonify
from flask_cors import CORS
from langchain_ollama import OllamaLLM
from langchain_core.prompts import ChatPromptTemplate
import pandas as pd

# Initialize Flask app
app = Flask(__name__)
CORS(app)  # Enable CORS for all origins

# Initialize model and prompt template
chatTemplate = """
Answer the Question Below. If you are providing patient details, only provide information that can be found in the context and if you are providing something outside the context, mention that clearly.

Here is the conversation history: {context}

Don't repeat the information that is already provided in the conversation history, unless asked to.

Here are the patient details: {patient_details}

Here are the nurse notes: {nurse_notes}

Question: {question}
Prioritize information that is relevant to the question and start with mentioning any important alerts and updates and provide a clear and concise answer in a human-like manner.

Answer: 
"""

recordingTemplate = """
Answer the Question Below. If you are providing patient details, only provide information that can be found in the context and if you are providing something outside the context, mention that clearly.

Here is the conversation history: {context}

Here are the patient details: {patient_details}

Here is the patient note: {patient_note}

Confirm that the patient note has been recorded successfully and generate a confirmation to keep the flow of the conversation.

Response:
"""

model = OllamaLLM(model="pulseAI")
chatPrompt = ChatPromptTemplate.from_template(chatTemplate)
recordPrompt = ChatPromptTemplate.from_template(recordingTemplate)
chatChain = chatPrompt | model
recordChain = recordPrompt | model

# Context storage: global and user-specific
if (model == "pulseAI"):
    global_context = """
"""
else:
   global_context = """
    You are a fellow digital nurse at the hospital reception, helping share patient data. Your name is Pulse AI. You are a voice assistant that helps with managing workforce and providing important updates on patients.
""" 
user_contexts = {}

# Load patient data
patient_data_path = "patient_data.csv"  # Path to the patient data file
patient_data = pd.read_csv(patient_data_path)

def get_patient_notes(patient_id):
    try:
        nurse_notes = pd.read_csv("nurse_notes.csv")
    except FileNotFoundError:
        return [{"date": "N/A", "note": "No notes file available."}]
    except Exception as e:
        return [{"date": "N/A", "note": f"Error reading nurse_notes.csv: {e}"}]

    # Ensure the PatientID column exists
    if "PatientID" not in nurse_notes.columns:
        return [{"date": "N/A", "note": "No PatientID found in notes."}]

    # Filter notes for the given PatientID
    notes = nurse_notes[nurse_notes["PatientID"] == patient_id]
    if notes.empty:
        return [{"date": "N/A", "note": "No notes available for this patient."}]

    # Ensure the output is a list of dictionaries with the expected keys
    return [
        {"date": row["Date"], "note": row["Note"]}
        for _, row in notes.iterrows()
    ]


def get_patient_details_by_room(room_number):
    patient_info = patient_data[patient_data["Room Number"] == room_number]
    if not patient_info.empty:
        patient = patient_info.iloc[0]
        patient_id = patient["Patient ID"]

        return (
            f"Patient Name: {patient['Patient Name']}, Room Number: {patient['Room Number']}, "
            f"Condition: {patient['Condition']}, Diagnosis: {patient['Diagnosis']}, "
            f"Undergoing Treatments: {patient['Undergoing Treatments']}, Assigned Nurse: {patient['Assigned Nurse ID']}, "
        ), patient_id
    return None, None

def get_patient_details_by_name(patient_name):
    patient_info = patient_data[patient_data["Patient Name"].str.contains(patient_name, case=False, na=False)]
    if not patient_info.empty:
        patient = patient_info.iloc[0]
        patient_id = patient["Patient ID"]
        
        return (
            f"Patient Name: {patient['Patient Name']}, Room Number: {patient['Room Number']}, "
            f"Condition: {patient['Condition']}, Diagnosis: {patient['Diagnosis']}, "
            f"Undergoing Treatments: {patient['Undergoing Treatments']}, Assigned Nurse: {patient['Assigned Nurse ID']}, "
        ), patient_id
    return None, None

@app.route('/test', methods=['GET'])
def test():
    return jsonify({"message": "Server is running!"}), 200

@app.route('/chat', methods=['POST'])
def chat():
    data = request.json
    user_id = data.get("user_id")
    user_input = data.get("message")

    # Detect language
    try:
        language = detect(user_input)
        current_global_context = global_context + f"\nRespond in {'Finnish' if language == 'fi' else 'English'}."
    except Exception as e:
        return jsonify({"error": f"Language detection failed: {str(e)}"}), 500

    # Initialize user context if it doesn't exist
    if user_id not in user_contexts:
        user_contexts[user_id] = {
            "patient_id": None,
            "patient_details": None,
            "nurse_notes": [],
            "chat_history": []
        }

    context = current_global_context

    # Check for patient-specific queries
    patient_details, patient_id = None, None
    for room_number in patient_data["Room Number"].unique():
        if str(room_number) in user_input:
            patient_details, patient_id = get_patient_details_by_room(room_number)
            break

    if not patient_details:
        for name in patient_data["Patient Name"]:
            if name.lower() in user_input.lower():
                patient_details, patient_id = get_patient_details_by_name(name)
                break

    if patient_details:
        user_contexts[user_id]["patient_id"] = patient_id
        user_contexts[user_id]["patient_details"] = patient_details
        user_contexts[user_id]["nurse_notes"] = get_patient_notes(patient_id)

    # Include chat history in context
    context += "\nChat History:\n" + "\n".join(
        [f"User: {entry['user']}\nAI: {entry['ai']}" for entry in user_contexts[user_id]["chat_history"]]
    )

    print(user_contexts[user_id])

    print("Data being sent to AI model:")
    print(context) 

    # Generate AI's response
    result = chatChain.invoke({"context": context, "question": user_input, "patient_details": user_contexts[user_id]["patient_details"], "nurse_notes": user_contexts[user_id]["nurse_notes"]})

    # Update chat history
    user_contexts[user_id]["chat_history"].append({"user": user_input, "ai": result})

    return jsonify({"response": result})

@app.route('/set_global_context', methods=['POST'])
def set_global_context():
    global global_context
    data = request.json
    global_context = data.get("context")
    return jsonify({"message": "Global context updated successfully"}), 200

@app.route('/record', methods=['POST'])
def record():
    data = request.json
    user_id = data.get("user_id")
    patient_note = data.get("note")
    current_date = pd.to_datetime("now").strftime("%Y-%m-%d %H:%M:%S")

    # Ensure user context exists for the user_id
    if user_id not in user_contexts:
        user_contexts[user_id] = {
            "patient_id": None,
            "patient_details": None,
            "nurse_notes": [],
            "chat_history": []
        }

    # Attempt to find patient_id if not already present in user context
    if not user_contexts[user_id]["patient_id"]:
        patient_details, patient_id = None, None

        # Check for room number in the new note
        for room_number in patient_data["Room Number"].unique():
            if str(room_number) in patient_note:
                patient_details, patient_id = get_patient_details_by_room(room_number)
                if patient_details:
                    break  # Found matching room number, exit loop

        # If no room number found, check for patient name in the new note
        if not patient_details:
            for name in patient_data["Patient Name"]:
                if name.lower() in patient_note.lower():  # Case-insensitive check
                    patient_details, patient_id = get_patient_details_by_name(name)
                    if patient_details:
                        break  # Found matching name, exit loop

        # Update user context with identified patient details
        if patient_details:
            user_contexts[user_id]["patient_id"] = patient_id
            user_contexts[user_id]["patient_details"] = patient_details
            user_contexts[user_id]["nurse_notes"] = get_patient_notes(patient_id)
        else:
            return jsonify({"error": "Patient room number or name required to save note."}), 400

    # If no patient_id is found even after checking, return an error
    if not user_contexts[user_id]["patient_id"]:
        return jsonify({"error": "Patient room number or name required to save note."}), 400

    # Save the note in the context
    user_contexts[user_id]["nurse_notes"].append({"date": current_date, "note": patient_note})

    # Append to the CSV file
    new_note = f"{current_date}, {user_id}, {user_contexts[user_id]['patient_id']}, {patient_note}\n"
    try:
        with open("nurse_notes.csv", 'a') as file:
            file.write(new_note)
    except FileNotFoundError:
        with open("nurse_notes.csv", 'w') as file:
            file.write("Date, NurseID, PatientID, Note\n")
            file.write(new_note)

    # Generate confirmation response using AI
    context = global_context
    context += "\nChat History:\n" + "\n".join(
        [f"User: {entry['user']}\nAI: {entry['ai']}" for entry in user_contexts[user_id]["chat_history"]]
    )

    result = recordChain.invoke({
        "context": context,
        "patient_details": user_contexts[user_id]["patient_details"],
        "patient_note": patient_note
    })

    print(result)

    # Update chat history
    user_contexts[user_id]["chat_history"].append({"user": patient_note, "ai": result})

    return jsonify({"response": result}), 200


if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=5000)
