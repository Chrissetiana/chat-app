package com.chrissetiana.chatapp;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.firebase.ui.auth.AuthUI;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    public static final int RC_SIGN_IN = 1;
    public static final int RC_PHOTO_PICKER = 2;
    public static final int DEFAULT_MSG_LENGTH_LIMIT = 1000;
    public static final String CHAT_MESSAGE_LENGTH = "chat_msg_len";
    public static final String ANONYMOUS = "anonymous";
    public static final String TAG = "MainActivity";

    private ListView messageListView;
    private MessageAdapter messageAdapter;
    private ProgressBar progressBar;
    private ImageButton photoPickerButton;
    private EditText messageEditText;
    private Button sendButton;
    private String username;

    private FirebaseDatabase firebaseDatabase;
    private DatabaseReference databaseReference;
    private ChildEventListener eventListener;
    private FirebaseAuth firebaseAuth;
    private FirebaseAuth.AuthStateListener authStateListener;
    private FirebaseStorage firebaseStorage;
    private StorageReference storageReference;
    private FirebaseRemoteConfig firebaseConfig;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        username = ANONYMOUS;

        // Initialize database
        firebaseDatabase = FirebaseDatabase.getInstance();
        firebaseAuth = FirebaseAuth.getInstance();
        firebaseStorage = FirebaseStorage.getInstance();
        firebaseConfig = FirebaseRemoteConfig.getInstance();

        // Create database node child
        databaseReference = firebaseDatabase.getReference().child("messages");
        storageReference = firebaseStorage.getReference().child("chat_photos");

        // Initialize references to views
        progressBar = findViewById(R.id.progressBar);
        messageListView = findViewById(R.id.messageListView);
        photoPickerButton = findViewById(R.id.photoPickerButton);
        messageEditText = findViewById(R.id.messageEditText);
        sendButton = findViewById(R.id.sendButton);

        // Initialize message ListView and its adapter
        List<ChatMessage> chatMessages = new ArrayList<>();
        messageAdapter = new MessageAdapter(this, R.layout.item_message, chatMessages);
        messageListView.setAdapter(messageAdapter);

        // Initialize progress bar
        progressBar.setVisibility(ProgressBar.INVISIBLE);

        // ImagePickerButton shows an image picker to upload a image for a message
        photoPickerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/jpeg");
                intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
                startActivityForResult(Intent.createChooser(intent, "Complete action using"), RC_PHOTO_PICKER);
            }
        });

        // Enable Send button when there's text to send
        messageEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                if (charSequence.toString().trim().length() > 0) {
                    sendButton.setEnabled(true);
                } else {
                    sendButton.setEnabled(false);
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });

        messageEditText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(DEFAULT_MSG_LENGTH_LIMIT)});

        // Send button sends a message and clears the EditText
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ChatMessage chatMessage = new ChatMessage(messageEditText.getText().toString(), username, null);

                databaseReference.push().setValue(chatMessage);

                // Clear input box
                messageEditText.setText("");
            }
        });

        authStateListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                FirebaseUser user = firebaseAuth.getCurrentUser();

                if (user != null) {
                    // user is signed in
                    onSignedInInitialize(user.getDisplayName());
                } else {
                    // user is signed out
                    onSignedOutCleanup();
                    startActivityForResult(
                            // Get an instance of AuthUI based on the default app
                            AuthUI.getInstance()
                                    .createSignInIntentBuilder()
                                    .setAvailableProviders(Arrays.asList(
                                            new AuthUI.IdpConfig.GoogleBuilder().build(),
                                            new AuthUI.IdpConfig.EmailBuilder().build()))
                                    .setIsSmartLockEnabled(false)
                                    .build(), RC_SIGN_IN);
                }
            }
        };

        FirebaseRemoteConfigSettings configSettings = new FirebaseRemoteConfigSettings.Builder()
                .setDeveloperModeEnabled(BuildConfig.DEBUG)
                .build();

        firebaseConfig.setConfigSettings(configSettings);

        Map<String, Object> defaultConfigMap = new HashMap<>();
        defaultConfigMap.put(CHAT_MESSAGE_LENGTH, DEFAULT_MSG_LENGTH_LIMIT);

        firebaseConfig.setDefaults(defaultConfigMap);

        fetchConfig();
    }

    private void fetchConfig() {
        long cacheExpiration = 3600;

        if (firebaseConfig.getInfo().getConfigSettings().isDeveloperModeEnabled()) {
            cacheExpiration = 0;
        }

        firebaseConfig.fetch(cacheExpiration)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        firebaseConfig.activateFetched();
                        applyLengthLimit();
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.w(TAG, "Error fetching config", e);
                        applyLengthLimit();
                    }
                });
    }

    private void applyLengthLimit() {
        Long chat_limit = firebaseConfig.getLong(CHAT_MESSAGE_LENGTH);
        messageEditText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(chat_limit.intValue())});
        Log.d(TAG, CHAT_MESSAGE_LENGTH + " = " + chat_limit);
    }

    private void onSignedInInitialize(String user) {
        username = user;
        attachDatabaseReadListener();
    }

    private void onSignedOutCleanup() {
        username = ANONYMOUS;
        messageAdapter.clear();
        detachDatabaseReadListener();
    }

    private void attachDatabaseReadListener() {
        // Event listener will listen to changes made on the node child
        if (eventListener == null) {
            eventListener = new ChildEventListener() {
                @Override
                public void onChildAdded(@NonNull DataSnapshot dataSnapshot, @Nullable String s) {
                    ChatMessage chatMessage = dataSnapshot.getValue(ChatMessage.class);
                    messageAdapter.add(chatMessage);
                }

                @Override
                public void onChildChanged(@NonNull DataSnapshot dataSnapshot, @Nullable String s) {

                }

                @Override
                public void onChildRemoved(@NonNull DataSnapshot dataSnapshot) {

                }

                @Override
                public void onChildMoved(@NonNull DataSnapshot dataSnapshot, @Nullable String s) {

                }

                @Override
                public void onCancelled(@NonNull DatabaseError databaseError) {

                }
            };

            databaseReference.addChildEventListener(eventListener);
        }
    }

    private void detachDatabaseReadListener() {
        if (eventListener != null) {
            databaseReference.removeEventListener(eventListener);
            eventListener = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.sign_out_menu:
                AuthUI.getInstance().signOut(this);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == RC_SIGN_IN) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "SIGN-IN SUCCESSFUL", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "SIGN-IN CANCELLED", Toast.LENGTH_SHORT).show();
                finish();
            }
        } else if (requestCode == RC_PHOTO_PICKER) {
            if (resultCode == RESULT_OK) {
                Uri selectedImage = data.getData();
                StorageReference photoRef = storageReference.child(selectedImage.getLastPathSegment());

                // Upload file to Firebase storage
                photoRef.putFile(selectedImage).addOnSuccessListener(this, new OnSuccessListener<UploadTask.TaskSnapshot>() {
                    @Override
                    public void onSuccess(UploadTask.TaskSnapshot taskSnapshot) {
//                        Uri downloadUri = taskSnapshot.getDownloadUrl();
                        Uri downloadUri = Uri.parse("https://chrissetiana.github.io");
                        ChatMessage chatMessage = new ChatMessage(null, username, downloadUri.toString());
                        databaseReference.push().setValue(chatMessage);
                    }
                });
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        firebaseAuth.addAuthStateListener(authStateListener);
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (authStateListener != null) {
            firebaseAuth.removeAuthStateListener(authStateListener);
        }

        detachDatabaseReadListener();
        messageAdapter.clear();
    }
}
