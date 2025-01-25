# Required imports
from langdetect import detect
from flask import Flask, request, jsonify
from flask_cors import CORS
from langchain_ollama import OllamaLLM
from langchain_core.prompts import ChatPromptTemplate
import pandas as pd
import time

# Initialize Flask app
app = Flask(__name__)
CORS(app)  # Enable CORS for all origins

# Initialize model and prompt template
chatTemplate = """
Answer the Question Below. If you are providing patient details, only provide information that can be found in the context, and if you are providing something outside the context, mention that clearly.

Here is the conversation history: {context}

Don't repeat the information that is already provided in the conversation history, unless asked to.

Here are the patient details with a timestamp of their last update: {patient_details}

Here are the nurse notes with timestamps of when they were updated: {nurse_notes_result}

Question: {question}

Prioritize information that is relevant to the question and start with mentioning any important alerts and updates based on timestamps and provide a clear and concise answer in a human-like manner.

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

nurseNotesTemplate = """
Answer the Question Below. If you are providing patient details, only provide information that can be found in the context and if you are providing something outside the context, mention that clearly.

Here are the nurse notes: 
{nurse_notes}

Given the list of these notes, group the notes by a common theme, and based on the date, provide the latest update for a particular theme. Don't provide any reasoning.
Response:
"""

model = OllamaLLM(model="pulseAITiny")
chatPrompt = ChatPromptTemplate.from_template(chatTemplate)
recordPrompt = ChatPromptTemplate.from_template(recordingTemplate)
nurseNotesPrompt = ChatPromptTemplate.from_template(nurseNotesTemplate)
chatChain = chatPrompt | model
nurseNotesChain = nurseNotesPrompt | model
recordChain = recordPrompt | model

# Context storage: global and user-specific
global_context = """You are a fellow digital nurse at the hospital reception, helping share patient data. Your name is Pulse AI. You are a voice assistant that helps with managing workforce and providing important updates on patients."""
user_contexts = {}

# Load patient data
patient_data_path = "patient_data.csv"  # Path to the patient data file
patient_data = pd.read_csv(patient_data_path)

def get_patient_notes(patient_id):
    try:
        nurse_notes = pd.read_csv("nurse_notes.csv")
        print(f"Processing nurse notes for Patient ID: {patient_id}")
    except FileNotFoundError:
        return [{"date": "N/A", "note": "No notes file available."}]
    except Exception as e:
        return [{"date": "N/A", "note": f"Error reading nurse_notes.csv: {e}"}]

    if "PatientID" not in nurse_notes.columns:
        return [{"date": "N/A", "note": "No PatientID found in notes."}]

    notes = nurse_notes[nurse_notes["PatientID"] == patient_id]
    if notes.empty:
        return [{"date": "N/A", "note": "No notes available for this patient."}]

    notes_list = [
        {"date": row["Date"], "note": row["Note"]}
        for _, row in notes.iterrows()
    ]

    if not notes_list:
        return [{"date": "N/A", "note": "No notes available to process."}]

    try:
        print("Processing nurse notes using AI...")
        nurse_start_time = time.time()
        nurse_notes_result = nurseNotesChain.invoke({"nurse_notes": notes_list})
        print(f"Nurse notes processed for Patient ID: {patient_id}")
        print(nurse_notes_result)
        nurse_end_time = time.time()
        print(f"Nurse Notes AI Procssing time: {nurse_end_time - nurse_start_time} seconds")
        return nurse_notes_result
    except Exception as e:
        return [{"date": "N/A", "note": f"Error processing nurse notes: {e}"}]

def get_patient_details_by_room(room_number):
    patient_info = patient_data[patient_data["Room Number"] == room_number]
    if not patient_info.empty:
        patient = patient_info.iloc[0]
        patient_id = patient["Patient ID"]
        print(f"Patient detected by room: {room_number}, Name: {patient['Patient Name']}")
        return (
            f"Patient Name: {patient['Patient Name']}, Room Number: {patient['Room Number']}, "
            f"Condition: {patient['Condition']}, Diagnosis: {patient['Diagnosis']}, "
            f"Undergoing Treatments: {patient['Undergoing Treatments']}, Assigned Nurse: {patient['Assigned Nurse ID'],}, Last Update: {patient['Last Update']}, "
        ), patient_id
    return None, None

def get_patient_details_by_name(patient_name):
    patient_info = patient_data[patient_data["Patient Name"].str.contains(patient_name, case=False, na=False)]
    if not patient_info.empty:
        patient = patient_info.iloc[0]
        patient_id = patient["Patient ID"]
        print(f"Patient detected by name: {patient_name}")
        return (
            f"Patient Name: {patient['Patient Name']}, Room Number: {patient['Room Number']}, "
            f"Condition: {patient['Condition']}, Diagnosis: {patient['Diagnosis']}, "
            f"Undergoing Treatments: {patient['Undergoing Treatments']}, Assigned Nurse: {patient['Assigned Nurse ID']}, Last Update: {patient['Last Update']}, "
        ), patient_id
    return None, None

@app.route('/chat', methods=['POST'])
def chat():
    start_time = time.time()
    data = request.json
    user_id = data.get("user_id")
    user_input = data.get("message")
    user_language = data.get("language")

    print("Request recieved from user:", user_id)
    print("Message:", user_input)
    print("Language:", user_language)


    current_global_context = global_context + f"\nRespond in {'Finnish' if user_language == 'fi' else 'English'}."


    # # Detect language
    # try:
    #     language = detect(user_input)
    #     current_global_context = global_context + f"\nRespond in {'Finnish' if language == 'fi' else 'English'}."
    # except Exception as e:
    #     return jsonify({"error": f"Language detection failed: {str(e)}"}), 500

    if user_id not in user_contexts:
        user_contexts[user_id] = {
            "patient_id": None,
            "patient_details": None,
            "nurse_notes": [],
            "chat_history": []
        }

    context = current_global_context

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

    context += "\nChat History:\n" + "\n".join(
        [f"User: {entry['user']}\nAI: {entry['ai']}" for entry in user_contexts[user_id]["chat_history"]]
    )

    print("Calling chat chain...")
    ai_start_time = time.time()
    chat_result = chatChain.invoke({
        "context": context,
        "question": user_input,
        "patient_details": user_contexts[user_id]["patient_details"],
        "nurse_notes_result": user_contexts[user_id]["nurse_notes"]
    })
    print("Chat chain response received.")
    ai_end_time = time.time()
    print(f"Chat Response time: {ai_end_time - ai_start_time} seconds")

    user_contexts[user_id]["chat_history"].append({"user": user_input, "ai": chat_result})

    end_time = time.time()
    print(f"Total Response time: {end_time - start_time} seconds")

    return jsonify({"response": chat_result})

@app.route('/record', methods=['POST'])
def record():
    data = request.json
    user_id = data.get("user_id")
    patient_note = data.get("message")
    current_date = pd.to_datetime("now").strftime("%Y-%m-%d %H:%M:%S")


    print("Request recieved from user:", user_id)
    print("Message:", patient_note)
    print("Language:", data.get("language"))

    if not patient_note:
        return jsonify({"error": "Patient data couldn't be saved due to missing note content."}), 400

    # Step 1: Extract potential patient information from the note
    potential_patient_details, potential_patient_id = None, None
    for room_number in patient_data["Room Number"].unique():
        if str(room_number) in patient_note:
            potential_patient_details, potential_patient_id = get_patient_details_by_room(room_number)
            if potential_patient_details:
                break

    if not potential_patient_details:
        for name in patient_data["Patient Name"]:
            if name.lower() in patient_note.lower():
                potential_patient_details, potential_patient_id = get_patient_details_by_name(name)
                if potential_patient_details:
                    break

    if not potential_patient_details:
        return jsonify({"error": "Patient room number or name required to save note."}), 400

    # Step 2: Check if patient is already in user context
    if user_id not in user_contexts:
        user_contexts[user_id] = {
            "patient_id": None,
            "patient_details": None,
            "nurse_notes": [],
            "chat_history": []
        }

    if (
        user_contexts[user_id]["patient_id"] == potential_patient_id and
        user_contexts[user_id]["patient_details"] == potential_patient_details
    ):
        # Patient details are already in context
        patient_id = user_contexts[user_id]["patient_id"]
        patient_details = user_contexts[user_id]["patient_details"]
    else:
        # Update user context with new patient details
        patient_id = potential_patient_id
        patient_details = potential_patient_details
        user_contexts[user_id]["patient_id"] = patient_id
        user_contexts[user_id]["patient_details"] = patient_details

    # Step 3: Update nurse notes in user context
    user_contexts[user_id]["nurse_notes"] = get_patient_notes(patient_id)

    # Add the new note to nurse notes
    new_note = f"{current_date}: {patient_note}\n"
    if isinstance(user_contexts[user_id]["nurse_notes"], str):
        user_contexts[user_id]["nurse_notes"] += new_note
    else:
        user_contexts[user_id]["nurse_notes"] = new_note

    # Write the note to the nurse_notes.csv file
    new_note_csv = f"{current_date}, {user_id}, {patient_id}, {patient_note}\n"
    try:
        with open("nurse_notes.csv", 'a') as file:
            file.write(new_note_csv)
    except FileNotFoundError:
        with open("nurse_notes.csv", 'w') as file:
            file.write("Date, NurseID, PatientID, Note\n")
            file.write(new_note_csv)

    # Step 4: Update context for record chain
    context = global_context
    context += "\nChat History:\n" + "\n".join(
        [f"User: {entry['user']}\nAI: {entry['ai']}" for entry in user_contexts[user_id]["chat_history"]]
    )

    print("Calling record chain...")
    result = recordChain.invoke({
        "context": context,
        "patient_details": user_contexts[user_id]["patient_details"],
        "patient_note": patient_note
    })
    print("Record chain response received.")

    # Add the result to chat history
    user_contexts[user_id]["chat_history"].append({"user": patient_note, "ai": result})

    return jsonify({"response": result}), 200



@app.route('/set_global_context', methods=['POST'])
def set_global_context():
    global global_context
    data = request.json
    global_context = data.get("context")
    print("Global context updated.")
    return jsonify({"message": "Global context updated successfully"}), 200

@app.route('/test', methods=['GET'])
def test():
    return jsonify({"message": "Server is running!"}), 200

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=5000)
