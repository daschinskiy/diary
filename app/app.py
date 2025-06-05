from flask import Flask, render_template, request, redirect, session
import mysql.connector
import requests
from datetime import datetime
import os

app = Flask(__name__)
app.secret_key = 'mysecret'

DB_CONFIG = {
    'host': os.environ.get('DB_HOST', 'localhost'),
    'user': os.environ.get('DB_USER', 'root'),
    'password': os.environ.get('DB_PASSWORD', 'password'),
    'database': os.environ.get('DB_NAME', 'diary')
}

db = mysql.connector.connect(**DB_CONFIG)
cursor = db.cursor()

cursor.execute("""
CREATE TABLE IF NOT EXISTS users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50),
    password VARCHAR(50)
)
""")

cursor.execute("""
CREATE TABLE IF NOT EXISTS posts (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50),
    date DATE,
    topic VARCHAR(100),
    message TEXT
)
""")
db.commit()

@app.route('/', methods=['GET', 'POST'])
def login():
    if request.method == 'POST':
        user = request.form['username']
        password = request.form['password']
        cursor.execute("SELECT * FROM users WHERE username=%s AND password=%s", (user, password))
        account = cursor.fetchone()
        if account:
            session['username'] = user
            return redirect('/diary')
    return render_template('login.html')

@app.route('/register', methods=['GET', 'POST'])
def register():
    if request.method == 'POST':
        user = request.form['username']
        password = request.form['password']
        cursor.execute("INSERT INTO users (username, password) VALUES (%s, %s)", (user, password))
        db.commit()
        return redirect('/')
    return render_template('register.html')

@app.route('/logout')
def logout():
    session.clear()
    return redirect('/')

@app.route('/diary', methods=['GET', 'POST'])
def diary():
    if 'username' not in session:
        return redirect('/')
    if request.method == 'POST':
        date = request.form['date']
        topic = request.form['topic']
        message = request.form['message']
        cursor.execute("INSERT INTO posts (username, date, topic, message) VALUES (%s, %s, %s, %s)",
                       (session['username'], date, topic, message))
        db.commit()
    cursor.execute("SELECT date, topic, message FROM posts WHERE username=%s", (session['username'],))
    posts = cursor.fetchall()
    return render_template('diary.html', username=session['username'], posts=posts)

@app.route('/diary_v2')
def diary_v2():
    if 'username' not in session:
        return redirect('/')
    url = 'https://api.nbrb.by/exrates/rates?periodicity=0'
    try:
        response = requests.get(url)
        rates = response.json()
    except:
        rates = []
    return render_template('diary_v2.html', username=session['username'], rates=rates)

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0')