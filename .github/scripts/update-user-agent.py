import requests
import re
import os
from bs4 import BeautifulSoup

def update_user_agent():
    url = "https://www.useragents.me/"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    }
    
    response = requests.get(url, headers=headers)
    if response.status_code != 200:
        print(f"Error fetching page: {response.status_code}")
        return

    soup = BeautifulSoup(response.text, 'html.parser')
    
    # The user provided a very specific selector. Let's try to find the textarea.
    # In BS4, we can look for the text "Latest Chrome Windows" and get the next textarea.
    
    new_ua = None
    
    # Strategy 1: Find the label "Latest Chrome Windows" and its associated textarea
    target_label = soup.find(string=re.compile("Latest Chrome Windows", re.I))
    if target_label:
        # Move up to find the container, then find the textarea within it
        parent_row = target_label.find_parent('tr')
        if parent_row:
            textarea = parent_row.find('textarea')
            if textarea:
                new_ua = textarea.text.strip()
                print(f"Found via 'Latest Chrome Windows' label: {new_ua}")

    # Strategy 2: Fallback to the first form-control textarea with Windows Chrome
    if not new_ua:
        textareas = soup.find_all('textarea', class_='form-control')
        for ta in textareas:
            ua = ta.text.strip()
            if "Windows NT 10.0" in ua and "Chrome" in ua and "Safari" in ua:
                new_ua = ua
                print(f"Found via fallback textarea search: {new_ua}")
                break
            
    if not new_ua:
        print("Could not find suitable User-Agent on page.")
        return

    print(f"Found new User-Agent: {new_ua}")
    
    file_path = "core/src/main/kotlin/fr/bluecxt/core/Const.kt"
    if not os.path.exists(file_path):
        print(f"File not found: {file_path}")
        return

    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    # Regex to find const val DEFAULT_USER_AGENT = "..."
    pattern = r'(const val DEFAULT_USER_AGENT = ")(.*?)(")'
    new_content = re.sub(pattern, rf'\1{new_ua}\3', content)

    if content == new_content:
        print("User-Agent is already up to date.")
    else:
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(new_content)
        print("Successfully updated DEFAULT_USER_AGENT.")

if __name__ == "__main__":
    update_user_agent()
